package jp.nano.rawgallery.camera;

import android.Manifest;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import java.io.Closeable;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit experimental transfer wake. No pairing reset, credential persistence, or Wi-Fi readiness claim. */
public final class CameraBleWake {
    public enum Outcome {
        PROTOCOL_ACCEPTED, AUTH_REJECTED, PROTOCOL_ERROR, TIMEOUT, DISCONNECTED, CANCELLED,
        PERMISSION_DENIED, BLUETOOTH_UNAVAILABLE, UNSUPPORTED_CAMERA, INVALID_INPUT
    }
    public static final class Result {
        public final Outcome outcome;
        public final int cameraStatus;
        public final boolean wakeCommandSent;
        private Result(Outcome outcome, int cameraStatus, boolean wakeCommandSent) {
            this.outcome = outcome; this.cameraStatus = cameraStatus; this.wakeCommandSent = wakeCommandSent;
        }
    }
    public interface Observer { void onFinished(Result result); }
    public interface ScanObserver {
        void onCamera(BluetoothDevice device, String displayName);
        void onFinished();
        void onError(Outcome outcome);
    }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<BluetoothDevice, Seen> seen = new HashMap<>();
    private static final class Seen {
        final int flags;
        final long time = SystemClock.elapsedRealtime();
        Seen(int flags) { this.flags = flags; }
    }
    public CameraBleWake(Context context) { this.context = context.getApplicationContext(); }
    private void dispatch(Runnable action) {
        if (Looper.myLooper() == main.getLooper()) action.run(); else main.post(action);
    }
    private boolean permission(boolean scanning) {
        if (Build.VERSION.SDK_INT >= 31)
            return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    && (!scanning || context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED);
        return !scanning || context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
    private BluetoothAdapter adapter() {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        return manager == null ? null : manager.getAdapter();
    }
    /** Observers run on main. Only target service/manufacturer advertisements are emitted, at most 32. */
    public Closeable scan(ScanObserver observer) {
        Scan task = new Scan(Objects.requireNonNull(observer)); main.post(task::start); return task;
    }
    /** Retain this helper after scan. Copies passcode; caller must erase its own char array. */
    public Closeable wake(BluetoothDevice device, char[] passcode, Observer observer) {
        Wake task = new Wake(device, passcode == null ? null : passcode.clone(), Objects.requireNonNull(observer));
        main.post(task::start); return task;
    }
    private final class Scan implements Closeable {
        final ScanObserver observer;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final Set<BluetoothDevice> emitted = new HashSet<>();
        boolean done;
        BluetoothLeScanner scanner;
        final Runnable timeout = () -> finish(null);
        final ScanCallback callback = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult result) { dispatch(() -> found(result)); }
            @Override public void onBatchScanResults(List<ScanResult> results) {
                dispatch(() -> { for (ScanResult result : results) found(result); });
            }
            @Override public void onScanFailed(int error) { dispatch(() -> finish(Outcome.PROTOCOL_ERROR)); }
        };
        Scan(ScanObserver observer) { this.observer = observer; }
        void start() {
            if (cancelled.get()) { finish(Outcome.CANCELLED); return; }
            if (!permission(true)) { finish(Outcome.PERMISSION_DENIED); return; }
            try {
                BluetoothAdapter adapter = adapter();
                if (adapter == null || !adapter.isEnabled() || (scanner = adapter.getBluetoothLeScanner()) == null) {
                    finish(Outcome.BLUETOOTH_UNAVAILABLE); return;
                }
                seen.clear();
                scanner.startScan(Collections.singletonList(new ScanFilter.Builder()
                                .setServiceUuid(new ParcelUuid(CameraBleProtocol.SERVICE)).build()),
                        new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback);
                main.postDelayed(timeout, 15000);
            } catch (SecurityException error) { finish(Outcome.PERMISSION_DENIED); }
            catch (RuntimeException error) { finish(Outcome.PROTOCOL_ERROR); }
        }
        void found(ScanResult result) {
            if (done || cancelled.get() || result == null || result.getDevice() == null) return;
            ScanRecord record = result.getScanRecord();
            if (record == null || record.getServiceUuids() == null
                    || !record.getServiceUuids().contains(new ParcelUuid(CameraBleProtocol.SERVICE))) return;
            byte[] data = record.getManufacturerSpecificData(0x04d0);
            if (!CameraBleProtocol.manufacturer(0x04d0, data)) {
                data = record.getManufacturerSpecificData(0x09f1);
                if (!CameraBleProtocol.manufacturer(0x09f1, data)) return;
            }
            String name = record.getDeviceName();
            if (name == null || name.isEmpty() || name.length() > 128) return;
            BluetoothDevice device = result.getDevice();
            if (!seen.containsKey(device) && seen.size() >= 32) return;
            seen.put(device, new Seen(CameraBleProtocol.advertisementFlags(data)));
            if (emitted.add(device)) observer.onCamera(device, name);
        }
        void finish(Outcome error) {
            if (done) return; done = true; main.removeCallbacks(timeout);
            if (scanner != null) {
                try { scanner.stopScan(callback); }
                catch (SecurityException revoked) { if (error == null) error = Outcome.PERMISSION_DENIED; }
                catch (RuntimeException ignored) { }
                scanner = null;
            }
            if (error == null) observer.onFinished(); else observer.onError(error);
        }
        @Override public void close() { cancelled.set(true); dispatch(() -> finish(Outcome.CANCELLED)); }
    }
    private enum Stage { CONNECT, DISCOVER, SUBSCRIBE, COMMAND_WRITE, RESPONSE, ACK_WRITE }
    private final class Wake implements Closeable {
        final BluetoothDevice device;
        final Observer observer;
        final AtomicBoolean cancelled = new AtomicBoolean();
        char[] passcode;
        BluetoothGatt gatt;
        BluetoothGattCharacteristic command, response;
        final UUID[] subscriptions = {CameraBleProtocol.COMMAND, CameraBleProtocol.RESPONSE, CameraBleProtocol.EVENT};
        int subscription, sequence = 1;
        boolean done, wakeSent, requestWake;
        Stage stage;
        CameraBleProtocol.Operation operation;
        CameraBleProtocol.Response parsed;
        byte[] pendingResponse, sensitiveFrame;
        final Runnable timeout = () -> finish(Outcome.TIMEOUT, -1);
        final BluetoothGattCallback callback = new BluetoothGattCallback() {
            @Override public void onConnectionStateChange(BluetoothGatt source, int status, int state) {
                dispatch(() -> {
                    if (!current(source)) return;
                    if (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
                        finish(Outcome.DISCONNECTED, -1); return;
                    }
                    if (state == BluetoothProfile.STATE_CONNECTED && stage == Stage.CONNECT) {
                        stage = Stage.DISCOVER; arm(10000);
                        try { if (!source.discoverServices()) finish(Outcome.PROTOCOL_ERROR, -1); }
                        catch (SecurityException revoked) { finish(Outcome.PERMISSION_DENIED, -1); }
                        catch (RuntimeException error) { finish(Outcome.PROTOCOL_ERROR, -1); }
                    }
                });
            }
            @Override public void onServicesDiscovered(BluetoothGatt source, int status) {
                dispatch(() -> {
                    if (!current(source) || stage != Stage.DISCOVER) return;
                    if (status != BluetoothGatt.GATT_SUCCESS) { finish(Outcome.PROTOCOL_ERROR, -1); return; }
                    BluetoothGattService service = source.getService(CameraBleProtocol.SERVICE);
                    if (service == null) { finish(Outcome.UNSUPPORTED_CAMERA, -1); return; }
                    command = service.getCharacteristic(CameraBleProtocol.COMMAND);
                    response = service.getCharacteristic(CameraBleProtocol.RESPONSE);
                    if (!canWrite(command) || !canWrite(response)) { finish(Outcome.UNSUPPORTED_CAMERA, -1); return; }
                    subscribe();
                });
            }
            @Override public void onDescriptorWrite(BluetoothGatt source, BluetoothGattDescriptor descriptor, int status) {
                dispatch(() -> {
                    if (!current(source) || stage != Stage.SUBSCRIBE) return;
                    if (!CameraBleProtocol.CCCD.equals(descriptor.getUuid())
                            || !subscriptions[subscription].equals(descriptor.getCharacteristic().getUuid())
                            || status != BluetoothGatt.GATT_SUCCESS) { finish(Outcome.PROTOCOL_ERROR, -1); return; }
                    if (++subscription < subscriptions.length) subscribe();
                    else send(CameraBleProtocol.Operation.AUTHENTICATE);
                });
            }
            @Override public void onCharacteristicWrite(BluetoothGatt source, BluetoothGattCharacteristic characteristic, int status) {
                dispatch(() -> {
                    if (!current(source)) return;
                    UUID expected = stage == Stage.COMMAND_WRITE ? CameraBleProtocol.COMMAND
                            : stage == Stage.ACK_WRITE ? CameraBleProtocol.RESPONSE : null;
                    if (expected == null || !expected.equals(characteristic.getUuid()) || status != BluetoothGatt.GATT_SUCCESS) {
                        finish(Outcome.PROTOCOL_ERROR, -1); return;
                    }
                    if (stage == Stage.COMMAND_WRITE) {
                        eraseFrame(); stage = Stage.RESPONSE;
                        if (pendingResponse != null) acceptResponse(pendingResponse);
                    } else completeOperation();
                });
            }
            @Override public void onCharacteristicChanged(BluetoothGatt source, BluetoothGattCharacteristic characteristic) {
                notifyValue(source, characteristic.getUuid(), characteristic.getValue());
            }
            @Override public void onCharacteristicChanged(BluetoothGatt source, BluetoothGattCharacteristic characteristic, byte[] value) {
                notifyValue(source, characteristic.getUuid(), value);
            }
        };
        Wake(BluetoothDevice device, char[] passcode, Observer observer) {
            this.device = device; this.passcode = passcode; this.observer = observer;
        }
        boolean current(BluetoothGatt source) {
            if (done || cancelled.get()) return false;
            return source == gatt;
        }
        boolean canWrite(BluetoothGattCharacteristic characteristic) {
            return characteristic != null && (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
        }
        void start() {
            if (cancelled.get()) { finish(Outcome.CANCELLED, -1); return; }
            if (!permission(false)) { finish(Outcome.PERMISSION_DENIED, -1); return; }
            try {
                byte[] validation = CameraBleProtocol.authenticate(1, passcode);
                Arrays.fill(validation, (byte) 0);
            } catch (IllegalArgumentException error) { finish(Outcome.INVALID_INPUT, -1); return; }
            Seen selected = seen.get(device);
            if (device == null || selected == null || SystemClock.elapsedRealtime() - selected.time > 60000) {
                finish(Outcome.UNSUPPORTED_CAMERA, -1); return;
            }
            requestWake = CameraBleProtocol.requiresWakeCommand(selected.flags);
            try {
                BluetoothAdapter adapter = adapter();
                if (adapter == null || !adapter.isEnabled()) { finish(Outcome.BLUETOOTH_UNAVAILABLE, -1); return; }
                stage = Stage.CONNECT; arm(20000);
                gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
                if (gatt == null) finish(Outcome.DISCONNECTED, -1);
            } catch (SecurityException error) { finish(Outcome.PERMISSION_DENIED, -1); }
            catch (RuntimeException error) { finish(Outcome.PROTOCOL_ERROR, -1); }
        }
        void arm(long millis) { main.removeCallbacks(timeout); main.postDelayed(timeout, millis); }
        void subscribe() {
            BluetoothGattCharacteristic characteristic = gatt.getService(CameraBleProtocol.SERVICE).getCharacteristic(subscriptions[subscription]);
            if (characteristic == null || (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) {
                finish(Outcome.UNSUPPORTED_CAMERA, -1); return;
            }
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CameraBleProtocol.CCCD);
            if (descriptor == null) { finish(Outcome.UNSUPPORTED_CAMERA, -1); return; }
            stage = Stage.SUBSCRIBE; arm(10000);
            if (done || cancelled.get()) return;
            try {
                if (!gatt.setCharacteristicNotification(characteristic, true)) {
                    finish(Outcome.PROTOCOL_ERROR, -1); return;
                }
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                if (!gatt.writeDescriptor(descriptor)) finish(Outcome.PROTOCOL_ERROR, -1);
            } catch (SecurityException revoked) { finish(Outcome.PERMISSION_DENIED, -1); }
            catch (RuntimeException error) { finish(Outcome.PROTOCOL_ERROR, -1); }
        }
        void send(CameraBleProtocol.Operation next) {
            operation = next; parsed = null; pendingResponse = null;
            try {
                sensitiveFrame = next == CameraBleProtocol.Operation.AUTHENTICATE
                        ? CameraBleProtocol.authenticate(sequence++, passcode) : CameraBleProtocol.transferWake(sequence++);
            } catch (IllegalArgumentException error) { finish(Outcome.INVALID_INPUT, -1); return; }
            erasePasscode();
            stage = Stage.COMMAND_WRITE; arm(next == CameraBleProtocol.Operation.TRANSFER_WAKE ? 20000 : 10000);
            command.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            command.setValue(sensitiveFrame);
            if (next == CameraBleProtocol.Operation.TRANSFER_WAKE) wakeSent = true;
            if (done || cancelled.get()) return;
            try { if (!gatt.writeCharacteristic(command)) finish(Outcome.PROTOCOL_ERROR, -1); }
            catch (SecurityException revoked) { finish(Outcome.PERMISSION_DENIED, -1); }
            catch (RuntimeException error) { finish(Outcome.PROTOCOL_ERROR, -1); }
        }
        void notifyValue(BluetoothGatt source, UUID uuid, byte[] value) {
            if (value == null || value.length > CameraBleProtocol.ATT_BYTES) {
                dispatch(() -> { if (current(source)) finish(Outcome.PROTOCOL_ERROR, -1); }); return;
            }
            byte[] copy = value.clone();
            dispatch(() -> {
                if (!current(source) || CameraBleProtocol.EVENT.equals(uuid)) return;
                if (CameraBleProtocol.COMMAND.equals(uuid)) {
                    if (operation == null || !CameraBleProtocol.receipt(copy)) finish(Outcome.PROTOCOL_ERROR, -1);
                    return;
                }
                if (!CameraBleProtocol.RESPONSE.equals(uuid) || operation == null) { finish(Outcome.PROTOCOL_ERROR, -1); return; }
                if (stage == Stage.COMMAND_WRITE) {
                    if (pendingResponse != null && !Arrays.equals(pendingResponse, copy)) finish(Outcome.PROTOCOL_ERROR, -1);
                    else pendingResponse = copy;
                } else if (stage == Stage.RESPONSE) acceptResponse(copy);
                else if (stage != Stage.ACK_WRITE || !Arrays.equals(pendingResponse, copy)) finish(Outcome.PROTOCOL_ERROR, -1);
            });
        }
        void acceptResponse(byte[] frame) {
            try { parsed = CameraBleProtocol.response(frame, operation); }
            catch (IllegalArgumentException error) { finish(Outcome.PROTOCOL_ERROR, -1); return; }
            pendingResponse = frame; stage = Stage.ACK_WRITE;
            response.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            response.setValue(parsed.acknowledgement());
            if (done || cancelled.get()) return;
            try { if (!gatt.writeCharacteristic(response)) finish(Outcome.PROTOCOL_ERROR, -1); }
            catch (SecurityException revoked) { finish(Outcome.PERMISSION_DENIED, -1); }
            catch (RuntimeException error) { finish(Outcome.PROTOCOL_ERROR, -1); }
        }
        void completeOperation() {
            if (!parsed.accepted(operation)) {
                finish(operation == CameraBleProtocol.Operation.AUTHENTICATE ? Outcome.AUTH_REJECTED : Outcome.PROTOCOL_ERROR, parsed.status);
            } else if (operation == CameraBleProtocol.Operation.AUTHENTICATE && requestWake) {
                send(CameraBleProtocol.Operation.TRANSFER_WAKE);
            } else finish(Outcome.PROTOCOL_ACCEPTED, parsed.status);
        }
        void erasePasscode() { if (passcode != null) { Arrays.fill(passcode, '\0'); passcode = null; } }
        void eraseFrame() {
            if (sensitiveFrame != null) { Arrays.fill(sensitiveFrame, (byte) 0); sensitiveFrame = null; }
            if (command != null) command.setValue(new byte[0]);
        }
        void finish(Outcome outcome, int status) {
            if (done) return; done = true; main.removeCallbacks(timeout); erasePasscode(); eraseFrame(); pendingResponse = null;
            if (gatt != null) {
                BluetoothGatt closing = gatt; gatt = null;
                try { closing.disconnect(); }
                catch (SecurityException revoked) { if (outcome == Outcome.PROTOCOL_ACCEPTED) outcome = Outcome.PERMISSION_DENIED; }
                catch (RuntimeException ignored) { }
                try { closing.close(); }
                catch (SecurityException revoked) { if (outcome == Outcome.PROTOCOL_ACCEPTED) outcome = Outcome.PERMISSION_DENIED; }
                catch (RuntimeException ignored) { }
            }
            command = null; response = null;
            observer.onFinished(new Result(outcome, status, wakeSent));
        }
        @Override public void close() { cancelled.set(true); dispatch(() -> finish(Outcome.CANCELLED, -1)); }
    }
}
