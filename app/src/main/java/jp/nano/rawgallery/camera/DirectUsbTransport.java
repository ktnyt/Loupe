package jp.nano.rawgallery.camera;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Synchronous USB Mass Storage Bulk-Only Transport for the Anker A83D1. */
final class DirectUsbTransport implements Closeable {
    private static final int BLOCK_SIZE = 512;
    private static final int MAX_BLOCKS = 128 * 1024 / BLOCK_SIZE;
    private static final int BULK_SLICE_SIZE = 16 * 1024;
    private static final int TIMEOUT_MS = 5_000;
    private static final int SYNC_TIMEOUT_MS = 30_000;
    private static final int READY_ATTEMPTS = 3;
    private static final int READY_DELAY_MS = 100;
    private static final int TARGET_LUN = 1;

    interface Wire extends Closeable {
        int maxLun(byte[] value);
        int send(byte[] data, int offset, int size, int timeout);
        int receive(byte[] data, int offset, int size, int timeout);
        void resetRecovery() throws IOException;
    }

    @FunctionalInterface
    interface BulkSlice {
        int transfer(byte[] data, int offset, int size, int timeout);
    }

    private final Wire wire;
    private final long[] capacity = new long[2];
    private int nextTag = 1;
    private boolean closed;
    private boolean failed;
    private boolean recoveryNeeded;
    private long targetFirst;
    private long targetEnd;
    private boolean targetSynced;
    private boolean targetDirty;

    private DirectUsbTransport(Wire wire) {
        this.wire = wire;
    }

    /** The caller must match the prepared USB address and prove safe ejection before opening. */
    static DirectUsbTransport open(UsbManager manager, UsbDevice device) throws IOException {
        if (device.getVendorId() != 0x21c4 || device.getProductId() != 0xb060) {
            throw new IOException("Unexpected USB reader");
        }
        if (!manager.hasPermission(device)) {
            throw new IOException("USB permission is required");
        }

        UsbInterface storage = null;
        for (int index = 0; index < device.getInterfaceCount(); index++) {
            UsbInterface candidate = device.getInterface(index);
            if (candidate.getInterfaceClass() == UsbConstants.USB_CLASS_MASS_STORAGE
                    && candidate.getInterfaceSubclass() == 6
                    && candidate.getInterfaceProtocol() == 0x50) {
                if (storage != null) {
                    throw new IOException("Multiple SCSI Bulk-Only interfaces");
                }
                storage = candidate;
            }
        }
        if (storage == null || storage.getAlternateSetting() != 0
                || storage.getEndpointCount() != 2) {
            throw new IOException("Unexpected SCSI Bulk-Only interface");
        }

        UsbEndpoint in = null;
        UsbEndpoint out = null;
        for (int index = 0; index < storage.getEndpointCount(); index++) {
            UsbEndpoint endpoint = storage.getEndpoint(index);
            int packetSize = endpoint.getMaxPacketSize();
            if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK
                    || endpoint.getEndpointNumber() == 0
                    || packetSize <= 0 || BULK_SLICE_SIZE % packetSize != 0) {
                throw new IOException("Unexpected SCSI Bulk-Only endpoint");
            }
            if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) {
                in = endpoint;
            } else {
                out = endpoint;
            }
        }
        if (in == null || out == null) {
            throw new IOException("SCSI Bulk-Only requires one IN and one OUT endpoint");
        }

        UsbDeviceConnection connection;
        try {
            connection = manager.openDevice(device);
        } catch (RuntimeException failure) {
            throw new IOException("Could not open USB reader", failure);
        }
        if (connection == null) {
            throw new IOException("Could not open USB reader");
        }
        try {
            if (!connection.claimInterface(storage, true)) {
                throw new IOException("Could not claim USB storage interface");
            }
        } catch (IOException | RuntimeException failure) {
            IOException claimFailure = failure instanceof IOException io
                    ? io : new IOException("Could not claim USB storage interface", failure);
            try {
                connection.close();
            } catch (RuntimeException closeFailure) {
                claimFailure.addSuppressed(closeFailure);
            }
            throw claimFailure;
        }
        return initialize(new AndroidWire(manager, device, connection, storage, in, out));
    }

    static DirectUsbTransport initialize(Wire wire) throws IOException {
        DirectUsbTransport transport = new DirectUsbTransport(wire);
        try {
            byte[] maxLun = new byte[1];
            if (wire.maxLun(maxLun) != 1 || maxLun[0] != TARGET_LUN) {
                throw new IOException("GET MAX LUN did not report exactly two LUNs");
            }
            for (int lun = 0; lun < transport.capacity.length; lun++) {
                transport.awaitReady(lun);
                byte[] data = new byte[8];
                byte[] cdb = new byte[10];
                cdb[0] = 0x25; // READ CAPACITY(10)
                transport.exchange(lun, cdb, true, data, 0, data.length, TIMEOUT_MS);
                ByteBuffer result = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
                long lastLba = Integer.toUnsignedLong(result.getInt());
                if (lastLba == 0xffff_ffffL || result.getInt() != BLOCK_SIZE) {
                    throw new IOException("Unsupported capacity or sector size on LUN " + lun);
                }
                transport.capacity[lun] = lastLba + 1;
            }
            return transport;
        } catch (IOException | RuntimeException failure) {
            IOException initFailure = failure instanceof IOException io
                    ? io : new IOException("USB reader initialization failed", failure);
            try {
                transport.close();
            } catch (IOException closeFailure) {
                initFailure.addSuppressed(closeFailure);
            }
            throw initFailure;
        }
    }

    private void awaitReady(int lun) throws IOException {
        byte[] cdb = new byte[6]; // TEST UNIT READY
        for (int attempt = 1; attempt <= READY_ATTEMPTS; attempt++) {
            if (exchange(lun, cdb, false, null, 0, 0, TIMEOUT_MS)) {
                return;
            }
            if (attempt < READY_ATTEMPTS) {
                try {
                    Thread.sleep(READY_DELAY_MS);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for LUN " + lun, failure);
                }
            }
        }
        throw new IOException("LUN " + lun + " did not become ready");
    }

    synchronized long sectors(int lun) {
        if (lun < 0 || lun >= capacity.length) {
            throw new IllegalArgumentException("Unexpected LUN " + lun);
        }
        return capacity[lun];
    }

    BlockDevice readView(int lun) {
        sectors(lun);
        return new BlockDevice() {
            @Override
            public int getBlockSize() {
                return BLOCK_SIZE;
            }

            @Override
            public void readBlocks(long firstLba, byte[] dst, int offset, int count) {
                try {
                    DirectUsbTransport.this.readBlocks(lun, firstLba, dst, offset, count);
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }
        };
    }

    synchronized void readBlocks(int lun, long firstLba, byte[] dst, int offset,
            int count) throws IOException {
        requireActive();
        requireRange(firstLba, count, sectors(lun));
        requireBuffer(dst, offset, (long) count * BLOCK_SIZE);
        int completed = 0;
        while (completed < count) {
            int blocks = Math.min(MAX_BLOCKS, count - completed);
            exchange(lun, blockCdb(0x28, firstLba + completed, blocks), true,
                    dst, offset + completed * BLOCK_SIZE, blocks * BLOCK_SIZE, TIMEOUT_MS);
            completed += blocks;
        }
    }

    synchronized void authorizeTarget(long firstLba, long count) {
        if (closed || failed || targetEnd != 0) {
            throw new IllegalStateException("Target write authorization is unavailable");
        }
        requireRange(firstLba, count, capacity[TARGET_LUN]);
        targetFirst = firstLba;
        targetEnd = firstLba + count;
    }

    synchronized void writeTargetBlocks(long lba, byte[] data, int offset, int count)
            throws IOException {
        requireActive();
        requireUnsyncedTarget();
        if (lba < targetFirst) {
            throw new IllegalArgumentException("Write starts before authorized LUN 1 range");
        }
        requireRange(lba, count, targetEnd);
        int size = checkedTransferSize(data, offset, count);
        targetDirty = true;
        exchange(TARGET_LUN, blockCdb(0x2a, lba, count), false, data, offset, size, TIMEOUT_MS);
    }

    synchronized void syncTarget() throws IOException {
        requireActive();
        requireUnsyncedTarget();
        byte[] cdb = new byte[10];
        cdb[0] = 0x35; // SYNCHRONIZE CACHE(10), IMMED clear, whole target LUN
        exchange(TARGET_LUN, cdb, false, null, 0, 0, SYNC_TIMEOUT_MS);
        targetDirty = false;
        targetSynced = true;
    }

    /** The caller must verify the complete saved digest before releasing this authorization. */
    synchronized void completeTarget() throws IOException {
        requireActive();
        if (!targetSynced) {
            throw new IllegalStateException("LUN 1 write range has not been synced");
        }
        targetFirst = 0;
        targetEnd = 0;
        targetSynced = false;
    }

    private static byte[] blockCdb(int opcode, long lba, int count) {
        ByteBuffer cdb = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN);
        cdb.put(0, (byte) opcode);
        cdb.putInt(2, (int) lba);
        cdb.putShort(7, (short) count);
        return cdb.array();
    }

    private static void requireRange(long firstLba, long count, long end) {
        if (firstLba < 0 || count <= 0 || firstLba >= end || count > end - firstLba) {
            throw new IllegalArgumentException("Sector range is outside the permitted LUN range");
        }
    }

    private static int checkedTransferSize(byte[] data, int offset, int count) {
        if (count > MAX_BLOCKS) {
            throw new IllegalArgumentException("Transfer is larger than 128 KiB");
        }
        int size = count * BLOCK_SIZE;
        requireBuffer(data, offset, size);
        return size;
    }

    private static void requireBuffer(byte[] data, int offset, long size) {
        if (data == null || offset < 0 || size > data.length - offset) {
            throw new IllegalArgumentException("Transfer does not fit the buffer");
        }
    }

    private void requireActive() throws IOException {
        if (closed || failed) {
            throw new IOException("BOT connection is closed or failed");
        }
    }

    private void requireUnsyncedTarget() {
        if (targetEnd == 0 || targetSynced) {
            throw new IllegalStateException("No active unsynced LUN 1 write range");
        }
    }

    /** Returns false only after a complete, failed TEST UNIT READY. */
    private boolean exchange(int lun, byte[] cdb, boolean in, byte[] data, int offset, int size,
            int completionTimeout) throws IOException {
        requireActive();
        int tag = nextTag++;
        ByteBuffer cbw = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN);
        cbw.putInt(0x43425355); // USBC
        cbw.putInt(tag);
        cbw.putInt(size);
        cbw.put((byte) (in ? 0x80 : 0));
        cbw.put((byte) lun);
        cbw.put((byte) cdb.length);
        cbw.put(cdb);
        byte[] csw = new byte[13];

        recoveryNeeded = true;
        try {
            requireLength("CBW", 31, wire.send(cbw.array(), 0, 31, TIMEOUT_MS));
            if (size != 0) {
                int transferred = in
                        ? wire.receive(data, offset, size, TIMEOUT_MS)
                        : wire.send(data, offset, size, TIMEOUT_MS);
                requireLength("data", size, transferred);
            }
            requireLength("CSW", 13, wire.receive(csw, 0, 13, completionTimeout));
            ByteBuffer status = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN);
            if (status.getInt() != 0x53425355 || status.getInt() != tag) { // USBS
                throw new IOException("BOT CSW signature or tag mismatch");
            }
            if (status.getInt() != 0) {
                throw new IOException("BOT CSW residue is nonzero");
            }
            int result = Byte.toUnsignedInt(status.get());
            if (result == 0 || result == 1) {
                // A well-formed pass or command failure completes the BOT transaction.
                recoveryNeeded = false;
            }
            if (result == 1 && cdb[0] == 0 && size == 0) {
                return false;
            }
            if (result != 0) {
                throw new IOException("BOT CSW status " + result + " on LUN " + lun);
            }
            return true;
        } catch (IOException failure) {
            failed = true;
            throw failure;
        } catch (RuntimeException failure) {
            failed = true;
            throw new IOException("USB bulk transfer failed", failure);
        }
    }

    private static void requireLength(String phase, int expected, int actual) throws IOException {
        if (actual != expected) {
            throw new IOException("BOT " + phase + " transferred " + actual + " of " + expected
                    + " bytes");
        }
    }

    static int transferInSlices(byte[] data, int offset, int size, int timeout,
            BulkSlice transfer) {
        int completed = 0;
        while (completed < size) {
            int slice = Math.min(BULK_SLICE_SIZE, size - completed);
            int actual = transfer.transfer(data, offset + completed, slice, timeout);
            if (actual < 0) {
                return actual;
            }
            if (actual != slice) {
                return actual < slice ? completed + actual : -1;
            }
            completed += slice;
        }
        return completed;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        IOException failure = null;
        if (targetDirty) {
            try {
                syncTarget();
            } catch (IOException | RuntimeException unsynced) {
                failure = new IOException("microSDの書き込みを確定できませんでした。", unsynced);
            }
        }
        closed = true;
        try {
            if (recoveryNeeded) {
                wire.resetRecovery();
            }
        } catch (IOException | RuntimeException resetFailure) {
            if (failure == null) failure = new IOException("USB BOT reset recovery failed", resetFailure);
            else failure.addSuppressed(resetFailure);
        }
        try {
            wire.close();
        } catch (IOException | RuntimeException closeFailure) {
            if (failure == null) {
                throw new IOException("Could not close USB reader", closeFailure);
            }
            failure.addSuppressed(closeFailure);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static final class AndroidWire implements Wire {
        private final UsbManager manager;
        private final UsbDevice device;
        private final UsbDeviceConnection connection;
        private final UsbInterface storage;
        private final UsbEndpoint in;
        private final UsbEndpoint out;

        AndroidWire(UsbManager manager, UsbDevice device, UsbDeviceConnection connection,
                UsbInterface storage, UsbEndpoint in, UsbEndpoint out) {
            this.manager = manager;
            this.device = device;
            this.connection = connection;
            this.storage = storage;
            this.in = in;
            this.out = out;
        }

        @Override
        public int maxLun(byte[] value) {
            return connection.controlTransfer(0xa1, 0xfe, 0, storage.getId(), value, 1, TIMEOUT_MS);
        }

        @Override
        public int send(byte[] data, int offset, int size, int timeout) {
            return transferInSlices(data, offset, size, timeout,
                    (buffer, start, length, limit) ->
                            connection.bulkTransfer(out, buffer, start, length, limit));
        }

        @Override
        public int receive(byte[] data, int offset, int size, int timeout) {
            return transferInSlices(data, offset, size, timeout,
                    (buffer, start, length, limit) ->
                            connection.bulkTransfer(in, buffer, start, length, limit));
        }

        @Override
        public void resetRecovery() throws IOException {
            UsbDevice attached = manager.getDeviceList().get(device.getDeviceName());
            if (!device.equals(attached) || connection.getFileDescriptor() < 0) {
                throw new IOException("The USB reader disconnected before BOT recovery");
            }
            // BOT reset recovery order: class reset, clear bulk IN halt, clear bulk OUT halt.
            requireControl(0x21, 0xff, storage.getId());
            requireControl(0x02, 0x01, in.getAddress());
            requireControl(0x02, 0x01, out.getAddress());
        }

        private void requireControl(int requestType, int request, int index) throws IOException {
            if (connection.controlTransfer(requestType, request, 0, index, null, 0, TIMEOUT_MS) != 0) {
                throw new IOException("USB BOT recovery control transfer failed");
            }
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            try {
                if (!connection.releaseInterface(storage)) {
                    failure = new IOException("USB storage interface release failed");
                }
            } catch (RuntimeException releaseFailure) {
                failure = new IOException("USB storage interface release failed", releaseFailure);
            }
            try {
                connection.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) {
                    throw new IOException("USB connection close failed", closeFailure);
                }
                failure.addSuppressed(closeFailure);
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
