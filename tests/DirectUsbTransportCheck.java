package jp.nano.rawgallery.camera;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Queue;

/** A local JVM regression check using a scripted USB wire. */
final class DirectUsbTransportCheck {
    private record Transfer(boolean in, byte[] bytes, int offset, int result) {}

    private static final class Script implements DirectUsbTransport.Wire {
        private final Queue<Transfer> transfers = new ArrayDeque<>();
        private int maxLun = 1;
        private int resets;
        private int closes;
        private boolean failClose;
        private boolean failReset;

        Script capacities() {
            return ready(1, 0, 0).capacity(2, 0, 99, 512)
                    .ready(3, 1, 0).capacity(4, 1, 0x8000_00ff, 512);
        }

        Script ready(int tag, int lun, int status) {
            return cbw(tag, lun, false, 0, "000000000000").csw(tag, 0, status);
        }

        Script capacity(int tag, int lun, int lastLba, int blockSize) {
            byte[] data = ByteBuffer.allocate(8).putInt(lastLba).putInt(blockSize).array();
            return cbw(tag, lun, true, 8, "25000000000000000000")
                    .in(data, 0).csw(tag, 0, 0);
        }

        Script cbw(int tag, int lun, boolean in, int length, String cdbHex) {
            return cbw(tag, lun, in, length, cdbHex, 31);
        }

        Script cbw(int tag, int lun, boolean in, int length, String cdbHex, int result) {
            byte[] cdb = HexFormat.of().parseHex(cdbHex);
            ByteBuffer cbw = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN);
            cbw.putInt(0x43425355).putInt(tag).putInt(length);
            cbw.put((byte) (in ? 0x80 : 0)).put((byte) lun).put((byte) cdb.length).put(cdb);
            transfers.add(new Transfer(false, cbw.array(), 0, result));
            return this;
        }

        Script in(byte[] bytes, int offset) {
            transfers.add(new Transfer(true, bytes, offset, bytes.length));
            return this;
        }

        Script out(byte[] bytes, int offset) {
            transfers.add(new Transfer(false, bytes, offset, bytes.length));
            return this;
        }

        Script csw(int tag, int residue, int status) {
            return in(ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(0x53425355).putInt(tag).putInt(residue).put((byte) status).array(), 0);
        }

        Script shortIn(int size, int result) {
            transfers.add(new Transfer(true, new byte[size], 0, result));
            return this;
        }

        @Override
        public int maxLun(byte[] value) {
            value[0] = (byte) maxLun;
            return 1;
        }

        @Override
        public int send(byte[] data, int offset, int size, int timeout) {
            Transfer transfer = next(false, offset, size, timeout);
            require(Arrays.equals(data, offset, offset + size,
                    transfer.bytes(), 0, size), "unexpected transmitted bytes");
            return transfer.result();
        }

        @Override
        public int receive(byte[] data, int offset, int size, int timeout) {
            Transfer transfer = next(true, offset, size, timeout);
            if (transfer.result() > 0) {
                System.arraycopy(transfer.bytes(), 0, data, offset, transfer.result());
            }
            return transfer.result();
        }

        private Transfer next(boolean in, int offset, int size, int timeout) {
            Transfer transfer = transfers.poll();
            require(transfer != null && transfer.in() == in && transfer.offset() == offset
                    && transfer.bytes().length == size && timeout > 0, "unexpected USB phase");
            return transfer;
        }

        @Override
        public void resetRecovery() throws IOException {
            resets++;
            if (failReset) throw new IOException("scripted BOT reset failure");
        }

        @Override
        public void close() throws IOException {
            closes++;
            require(transfers.isEmpty(), "unconsumed USB phases");
            if (failClose) {
                throw new IOException("scripted release failure");
            }
        }
    }

    private DirectUsbTransportCheck() {}

    public static void main(String[] args) throws IOException {
        readWriteAndSync();
        rejectUnexpectedCapacity();
        readiness();
        splitRead();
        reportReleaseFailure();
        rejectFailedSync();
        syncBeforeClosingAnInterruptedCopy();
        reportResetFailure();
        transferSlices();
        terminalFailure("short CBW", 0);
        terminalFailure("short data", 1);
        terminalFailure("short CSW", 2);
        terminalFailure("wrong CSW tag", 3);
        terminalFailure("nonzero CSW residue", 4);
        terminalFailure("failed CSW status", 5);
        terminalFailure("phase error CSW status", 6);
        System.out.println("BOT protocol and fail-closed checks passed");
    }

    private static void readWriteAndSync() throws IOException {
        byte[] source = new byte[512];
        source[0] = 42;
        byte[] write = new byte[128 * 1024];
        Arrays.fill(write, (byte) 64);
        Script wire = new Script().capacities()
                .cbw(5, 0, true, 512, "28000000006300000100").in(source, 1).csw(5, 0, 0)
                .cbw(6, 1, false, write.length, "2a008000000000010000").out(write, 2).csw(6, 0, 0)
                .cbw(7, 1, false, 0, "35000000000000000000").csw(7, 0, 0)
                .cbw(8, 1, false, 512, "2a000000000100000100").out(source, 0).csw(8, 0, 0)
                .cbw(9, 1, false, 0, "35000000000000000000").csw(9, 0, 0);
        try (DirectUsbTransport bot = DirectUsbTransport.initialize(wire)) {
            require(bot.sectors(0) == 100, "LUN0 capacity");
            require(bot.sectors(1) == 0x8000_0100L, "LUN1 capacity");
            byte[] read = new byte[513];
            BlockDevice sourceView = bot.readView(0);
            require(sourceView.getBlockSize() == 512, "source block size");
            rejects(IllegalArgumentException.class, () -> bot.readView(2));
            rejects(IllegalArgumentException.class, () -> sourceView.readBlocks(100, read, 0, 1));
            rejects(IllegalArgumentException.class, () -> sourceView.readBlocks(0, read, 2, 1));
            sourceView.readBlocks(99, read, 1, 1);
            require(read[1] == 42, "LUN0 data");
            rejects(IllegalStateException.class, () -> bot.writeTargetBlocks(0, write, 0, 1));
            rejects(IllegalStateException.class, bot::syncTarget);
            rejects(IllegalArgumentException.class, () -> bot.authorizeTarget(0, Long.MAX_VALUE));
            bot.authorizeTarget(0x8000_0000L, 256);
            rejects(IllegalStateException.class, () -> bot.authorizeTarget(0, 1));
            rejects(IllegalArgumentException.class,
                    () -> bot.writeTargetBlocks(0x7fff_ffffL, write, 0, 1));
            rejects(IllegalArgumentException.class,
                    () -> bot.writeTargetBlocks(0x8000_00ffL, write, 0, 2));
            rejects(IllegalArgumentException.class,
                    () -> bot.writeTargetBlocks(0x8000_0000L, write, 1, 256));
            byte[] paddedWrite = new byte[write.length + 2];
            System.arraycopy(write, 0, paddedWrite, 2, write.length);
            bot.writeTargetBlocks(0x8000_0000L, paddedWrite, 2, 256);
            rejects(IllegalStateException.class, bot::completeTarget);
            bot.syncTarget();
            rejects(IllegalStateException.class, () -> bot.writeTargetBlocks(0x8000_0000L, write, 0, 1));
            rejects(IllegalStateException.class, () -> bot.authorizeTarget(1, 1));
            rejects(IllegalStateException.class, bot::syncTarget);
            bot.completeTarget();
            rejects(IllegalStateException.class, bot::completeTarget);
            rejects(IllegalStateException.class, () -> bot.writeTargetBlocks(1, source, 0, 1));
            bot.authorizeTarget(1, 1);
            rejects(IllegalArgumentException.class, () -> bot.writeTargetBlocks(0x8000_0000L, write, 0, 1));
            bot.writeTargetBlocks(1, source, 0, 1);
            bot.syncTarget();
            bot.completeTarget();
        }
        require(wire.resets == 0 && wire.closes == 1, "healthy release");
    }

    private static void rejectFailedSync() throws IOException {
        byte[] data = new byte[512];
        Script wire = new Script().capacities()
                .cbw(5, 1, false, 512, "2a000000001400000100").out(data, 0).csw(5, 0, 0)
                .cbw(6, 1, false, 0, "35000000000000000000").csw(6, 0, 1);
        DirectUsbTransport bot = DirectUsbTransport.initialize(wire);
        bot.authorizeTarget(20, 1);
        bot.writeTargetBlocks(20, data, 0, 1);
        rejects(IOException.class, bot::syncTarget);
        rejects(IOException.class, bot::completeTarget);
        rejects(IllegalStateException.class, () -> bot.authorizeTarget(21, 1));
        rejects(IOException.class, bot::close);
        require(wire.resets == 0 && wire.closes == 1, "failed cache sync release");
    }

    private static void syncBeforeClosingAnInterruptedCopy() throws IOException {
        byte[] data = new byte[512];
        Script wire = new Script().capacities()
                .cbw(5, 1, false, 512, "2a000000001400000100").out(data, 0).csw(5, 0, 0)
                .cbw(6, 1, false, 0, "35000000000000000000").csw(6, 0, 0);
        try (DirectUsbTransport bot = DirectUsbTransport.initialize(wire)) {
            bot.authorizeTarget(20, 1);
            bot.writeTargetBlocks(20, data, 0, 1);
        }
        require(wire.resets == 0 && wire.closes == 1, "interrupted copy syncs before closure");
    }

    private static void reportResetFailure() throws IOException {
        Script wire = new Script().capacities()
                .cbw(5, 0, true, 512, "28000000001400000100", 30);
        wire.failReset = true;
        DirectUsbTransport bot = DirectUsbTransport.initialize(wire);
        rejects(UncheckedIOException.class,
                () -> bot.readView(0).readBlocks(20, new byte[512], 0, 1));
        rejects(IOException.class, bot::close);
        require(wire.resets == 1 && wire.closes == 1, "failed reset must still close");
    }

    private static void rejectUnexpectedCapacity() {
        Script wrongLunCount = new Script();
        wrongLunCount.maxLun = 0;
        for (Script wire : new Script[] {wrongLunCount,
                new Script().ready(1, 0, 0).capacity(2, 0, -1, 512),
                new Script().ready(1, 0, 0).capacity(2, 0, 99, 512)
                        .ready(3, 1, 0).capacity(4, 1, 100, 4096)}) {
            rejects(IOException.class, () -> DirectUsbTransport.initialize(wire));
            require(wire.resets == 0 && wire.closes == 1, "failed initialization release");
        }
    }

    private static void readiness() throws IOException {
        Script readyAfterRetry = new Script().ready(1, 0, 1)
                .ready(2, 0, 0).capacity(3, 0, 99, 512)
                .ready(4, 1, 1).ready(5, 1, 1).ready(6, 1, 0).capacity(7, 1, 199, 512);
        try (DirectUsbTransport bot = DirectUsbTransport.initialize(readyAfterRetry)) {
            require(bot.sectors(0) == 100 && bot.sectors(1) == 200, "readiness retry capacities");
        }
        require(readyAfterRetry.resets == 0 && readyAfterRetry.closes == 1, "readiness retry release");

        Script neverReady = new Script().ready(1, 0, 1).ready(2, 0, 1).ready(3, 0, 1);
        rejects(IOException.class, () -> DirectUsbTransport.initialize(neverReady));
        require(neverReady.resets == 0 && neverReady.closes == 1, "readiness retry cap");
        for (Script malformed : new Script[] {new Script().ready(1, 0, 2),
                new Script().cbw(1, 0, false, 0, "000000000000").csw(1, 1, 1),
                new Script().cbw(1, 0, false, 0, "000000000000").csw(2, 0, 1),
                new Script().cbw(1, 0, false, 0, "000000000000").shortIn(13, 12)}) {
            rejects(IOException.class, () -> DirectUsbTransport.initialize(malformed));
            require(malformed.resets == 1 && malformed.closes == 1, "malformed readiness release");
        }
    }

    private static void splitRead() throws IOException {
        byte[] first = new byte[256 * 512];
        byte[] second = new byte[512];
        Arrays.fill(first, (byte) 42);
        Arrays.fill(second, (byte) 7);
        final int offset = 19;
        Script wire = new Script().capacities()
                .cbw(5, 1, true, first.length, "28007fffffff00010000").in(first, offset).csw(5, 0, 0)
                .cbw(6, 1, true, second.length, "2800800000ff00000100")
                .in(second, offset + first.length).csw(6, 0, 0);
        byte[] read = new byte[offset + first.length + second.length + 1];
        try (DirectUsbTransport bot = DirectUsbTransport.initialize(wire)) {
            BlockDevice targetView = bot.readView(1);
            rejects(IllegalArgumentException.class,
                    () -> targetView.readBlocks(0, read, 0, Integer.MAX_VALUE));
            rejects(IllegalArgumentException.class,
                    () -> targetView.readBlocks(0, read, offset + 2, 257));
            targetView.readBlocks(0x7fff_ffffL, read, offset, 257);
        }
        require(read[0] == 0 && read[offset] == 42 && read[offset + first.length - 1] == 42
                && read[offset + first.length] == 7 && read[read.length - 2] == 7
                && read[read.length - 1] == 0, "split read offset and data");
    }

    private static void reportReleaseFailure() throws IOException {
        Script failedRelease = new Script().capacities();
        failedRelease.failClose = true;
        DirectUsbTransport bot = DirectUsbTransport.initialize(failedRelease);
        rejects(IOException.class, bot::close);
        bot.close();
        require(failedRelease.closes == 1, "failed release still closes once");

        Script failedInit = new Script();
        failedInit.maxLun = 0;
        failedInit.failClose = true;
        try {
            DirectUsbTransport.initialize(failedInit);
            throw new AssertionError("initialization must fail");
        } catch (IOException failure) {
            require(failure.getMessage().contains("GET MAX LUN")
                    && failure.getSuppressed().length == 1, "initialization preserves release failure");
        }
    }

    private static void transferSlices() {
        int slice = 16 * 1024;
        int dataOffset = 19;
        int timeout = 123;
        byte[] bytes = new byte[dataOffset + 2 * slice + 512];
        int[] calls = {0};
        int full = DirectUsbTransport.transferInSlices(bytes, dataOffset, 2 * slice + 512, timeout,
                (buffer, offset, size, limit) -> {
                    int index = calls[0]++;
                    require(buffer == bytes && offset == dataOffset + index * slice
                            && size == (index < 2 ? slice : 512)
                            && limit == timeout, "slice argument");
                    return size;
                });
        require(full == 2 * slice + 512 && calls[0] == 3, "full multi-slice completion");
        for (int response : new int[] {0, slice - 1, -1, slice + 1}) {
            calls[0] = 0;
            int partial = DirectUsbTransport.transferInSlices(bytes, dataOffset, 2 * slice + 512, timeout,
                    (buffer, offset, size, limit) -> calls[0]++ == 0 ? size : response);
            int expected = response >= 0 && response < slice ? slice + response : -1;
            require(calls[0] == 2 && partial == expected, "partial multi-slice stops immediately");
        }
    }

    private static void terminalFailure(String phase, int failure) throws IOException {
        boolean shortCbw = failure == 0;
        boolean shortData = failure == 1;
        boolean shortCsw = failure == 2;
        Script wire = new Script().capacities()
                .cbw(5, 1, false, 512, "2a000000001400000100", shortCbw ? 30 : 31);
        if (!shortCbw) {
            wire.transfers.add(new Transfer(false, new byte[512], 0, shortData ? 511 : 512));
            if (!shortData) {
                if (shortCsw) {
                    wire.shortIn(13, 12);
                } else {
                    wire.csw(failure == 3 ? 6 : 5, failure == 4 ? 1 : 0,
                            failure >= 5 ? failure - 4 : 0);
                }
            }
        }
        DirectUsbTransport bot = DirectUsbTransport.initialize(wire);
        bot.authorizeTarget(20, 1);
        rejects(IOException.class, () -> bot.writeTargetBlocks(20, new byte[512], 0, 1));
        rejects(IOException.class, () -> bot.writeTargetBlocks(20, new byte[512], 0, 1));
        rejects(UncheckedIOException.class,
                () -> bot.readView(1).readBlocks(20, new byte[512], 0, 1));
        rejects(IOException.class, bot::syncTarget);
        require(wire.resets == 0, phase + " attempted early recovery");
        rejects(IOException.class, bot::close);
        bot.close();
        require(wire.resets == (failure == 5 ? 0 : 1) && wire.closes == 1, phase + " release");
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws IOException;
    }

    private static void rejects(Class<? extends Exception> expected, CheckedAction action) {
        try {
            action.run();
        } catch (Exception observed) {
            require(expected.isInstance(observed), "unexpected failure " + observed);
            return;
        }
        throw new AssertionError("expected " + expected.getSimpleName());
    }

    private static void require(boolean condition, String why) {
        if (!condition) {
            throw new AssertionError(why);
        }
    }
}
