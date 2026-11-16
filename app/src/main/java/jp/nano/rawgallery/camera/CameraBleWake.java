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
        PROTOCOL_ACCEPTED, LOCATION_MODE_UNVERIFIED, AUTH_REJECTED, PROTOCOL_ERROR, TIMEOUT, DISCONNECTED, CANCELLED,
        PERMISSION_DENIED, BLUETOOTH_UNAVAILABLE, UNSUPPORTED_CAMERA, INVALID_INPUT
    }
    private enum Reason {
        STATE, DEADLINE, CONNECTION, DISCOVERY_START, DISCOVERY_CALLBACK, DESCRIPTOR_CALLBACK,
        WRITE_CALLBACK, NOTIFICATION_BOUNDS, RECEIPT_FORMAT, RESPONSE_ORDER, RESPONSE_FORMAT,
        CAMERA_REJECTED, WRITE_START, ACK_WRITE_START, SUBSCRIPTION_START, FRESH_ADVERTISEMENT
    }
    private enum NotificationRole { NONE, COMMAND, RESPONSE, EVENT, OTHER }
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
    private Scan activeScan;
    private Wake activeWake;
    private final Map<BluetoothDevice, Seen> seen = new HashMap<>();
    private static final class Seen {
        final int flags;
        final String name;
        final long time;
        Seen(int flags, String name, long time) { this.flags = flags; this.name = name; this.time = time; }
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
            if (activeWake != null) { finish(Outcome.CANCELLED); return; }
            if (activeScan != null) activeScan.close();
            activeScan = this;
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
            Seen advertisement = advertisement(result);
            if (advertisement == null) return;
            String name = advertisement.name;
            BluetoothDevice device = result.getDevice();
            if (!seen.containsKey(device) && seen.size() >= 32) return;
            seen.put(device, advertisement);
            if (emitted.add(device)) observer.onCamera(device, name);
        }
        void finish(Outcome error) {
            if (done) return; done = true; main.removeCallbacks(timeout);
            if (activeScan == this) activeScan = null;
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
    private Seen advertisement(ScanResult result) {
        if (result == null || result.getDevice() == null) return null;
        ScanRecord record = result.getScanRecord();
        if (record == null || record.getServiceUuids() == null
                || !record.getServiceUuids().contains(new ParcelUuid(CameraBleProtocol.SERVICE))) return null;
        byte[] data = record.getManufacturerSpecificData(0x04d0);
        if (!CameraBleProtocol.manufacturer(0x04d0, data)) {
            data = record.getManufacturerSpecificData(0x09f1);
            if (!CameraBleProtocol.manufacturer(0x09f1, data)) return null;
        }
        String name = record.getDeviceName();
        if (name == null || name.isEmpty() || name.length() > 128) return null;
        return new Seen(CameraBleProtocol.advertisementFlags(data), name, result.getTimestampNanos() / 1000000);
    }
    private enum Stage { WARM_CONNECT, WARM_WAIT_DISCONNECT, WARM_SETTLE, FRESH_SCAN, CONNECT, DISCOVER, SUBSCRIBE, COMMAND_WRITE, RESPONSE, ACK_WRITE }
    private final class Wake implements Closeable {
        final BluetoothDevice device;
        final Observer observer;
        final AtomicBoolean cancelled = new AtomicBoolean();
        char[] passcode;
        BluetoothGatt gatt;
        BluetoothGattCharacteristic command, response;
        final UUID[] subscriptions = {CameraBleProtocol.COMMAND, CameraBleProtocol.RESPONSE, CameraBleProtocol.EVENT};
        int subscription, sequence = 1;
        boolean done, wakeSent, warmConnected, warmDisconnected, freshAsicOn;
        int connectionCount;
        final long started = SystemClock.elapsedRealtime();
        int gattStatus = -1;
        NotificationRole notificationRole = NotificationRole.NONE;
        int notificationBytes = -1;
        int advertisementFlags;
        String selectedName;
        final CameraBleRecovery recovery = new CameraBleRecovery(started);
        BluetoothLeScanner scanner;
        long freshScanStarted;
        final Runnable settled = this::freshScan;
        final ScanCallback freshCallback = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult result) { dispatch(() -> freshFound(result)); }
            @Override public void onBatchScanResults(List<ScanResult> results) {
                dispatch(() -> { for (ScanResult result : results) freshFound(result); });
            }
            @Override public void onScanFailed(int error) { dispatch(() -> {
                if (!done && !cancelled.get() && stage == Stage.FRESH_SCAN) finish(Outcome.PROTOCOL_ERROR, -1);
            }); }
        };
        Stage stage;
        CameraBleProtocol.Operation operation;
        CameraBleProtocol.Response parsed;
        byte[] pendingResponse, sensitiveFrame;
        final Runnable timeout = () -> finish(Outcome.TIMEOUT, -1, Reason.DEADLINE);
        final BluetoothGattCallback callback = new BluetoothGattCallback() {
            @Override public void onConnectionStateChange(BluetoothGatt source, int status, int state) {
                dispatch(() -> {
                    if (!current(source)) return;
                    gattStatus = status;
                    if (stage == Stage.WARM_CONNECT || stage == Stage.WARM_WAIT_DISCONNECT) {
                        if (state == BluetoothProfile.STATE_DISCONNECTED) {
                            if (!recovery.disconnected()) { finish(Outcome.DISCONNECTED, -1, Reason.CONNECTION); return; }
                            warmDisconnected = true;
                            if (!closeWarmGatt()) return;
                            stage = Stage.WARM_SETTLE; main.removeCallbacks(timeout);
                            main.postDelayed(timeout, recovery.remaining(SystemClock.elapsedRealtime(), 90000));
                            main.postDelayed(settled, recovery.remaining(SystemClock.elapsedRealtime(), 3000));
                        } else if (status != BluetoothGatt.GATT_SUCCESS) {
                            finish(Outcome.DISCONNECTED, -1, Reason.CONNECTION);
                        } else if (state == BluetoothProfile.STATE_CONNECTED && recovery.connected()) {
                            warmConnected = true;
                            stage = Stage.WARM_WAIT_DISCONNECT; arm(30000);
                        }
                        return;
                    }
                    if (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
                        finish(Outcome.DISCONNECTED, -1, Reason.CONNECTION); return;
                    }
                    if (state == BluetoothProfile.STATE_CONNECTED && stage == Stage.CONNECT) {
                        stage = Stage.DISCOVER; arm(10000);
                        try { if (!source.discoverServices()) finish(Outcome.PROTOCOL_ERROR, -1, Reason.DISCOVERY_START); }
                        catch (SecurityException revoked) { finish(Outcome.PERMISSION_DENIED, -1); }
                        catch (RuntimeException error) { finish(Outcome.PROTOCOL_ERROR, -1); }
                    }
                });
            }
            @Override public void onServicesDiscovered(BluetoothGatt source, int status) {
                dispatch(() -> {
                    boolean accepted = current(source) && stage == Stage.DISCOVER;
                    android.util.Log.i("LoupeCameraBle", "discovery_callback accepted=" + accepted
                            + " gattStatus=" + status + " elapsedMs=" + (SystemClock.elapsedRealtime() - started));
                    if (!accepted) return;
                    gattStatus = status;
                    if (status != BluetoothGatt.GATT_SUCCESS) { finish(Outcome.PROTOCOL_ERROR, -1, Reason.DISCOVERY_CALLBACK); return; }
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
                    gattStatus = status;
                    if (!CameraBleProtocol.CCCD.equals(descriptor.getUuid())
                            || !subscriptions[subscription].equals(descriptor.getCharacteristic().getUuid())
                            || status != BluetoothGatt.GATT_SUCCESS) { finish(Outcome.PROTOCOL_ERROR, -1, Reason.DESCRIPTOR_CALLBACK); return; }
                    if (++subscription < subscriptions.length) subscribe();
                    else send(CameraBleProtocol.Operation.AUTHENTICATE);
                });
            }
            @Override public void onCharacteristicWrite(BluetoothGatt source, BluetoothGattCharacteristic characteristic, int status) {
                dispatch(() -> {
                    if (!current(source) || !normalPhase()) return;
                    gattStatus = status;
                    UUID expected = stage == Stage.COMMAND_WRITE ? CameraBleProtocol.COMMAND
                            : stage == Stage.ACK_WRITE ? CameraBleProtocol.RESPONSE : null;
                    if (expected == null || !expected.equals(characteristic.getUuid()) || status != BluetoothGatt.GATT_SUCCESS) {
                        finish(Outcome.PROTOCOL_ERROR, -1, Reason.WRITE_CALLBACK); return;
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
            if (recovery.remaining(SystemClock.elapsedRealtime(), 90000) == 0) { finish(Outcome.TIMEOUT, -1, Reason.DEADLINE); return false; }
            return source == gatt;
        }
        boolean canWrite(BluetoothGattCharacteristic characteristic) {
            return characteristic != null && (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
        }
        void start() {
            if (cancelled.get()) { finish(Outcome.CANCELLED, -1); return; }
            if (activeScan != null) activeScan.close();
            if (activeWake != null) activeWake.close();
            activeWake = this;
            if (!permission(false)) { finish(Outcome.PERMISSION_DENIED, -1); return; }
            if (!validPasscode()) { finish(Outcome.INVALID_INPUT, -1); return; }
            Seen selected = seen.get(device);
            if (device == null || selected == null || selected.time > SystemClock.elapsedRealtime() || SystemClock.elapsedRealtime() - selected.time > 60000) {
                finish(Outcome.UNSUPPORTED_CAMERA, -1); return;
            }
            advertisementFlags = selected.flags; selectedName = selected.name;
            recovery.start((advertisementFlags & 1) != 0);
            connect(recovery.phase == CameraBleRecovery.Phase.WARM_CONNECT);
        }
        void connect(boolean warm) {
            if (done || cancelled.get()) return;
            if (recovery.remaining(SystemClock.elapsedRealtime(), 90000) == 0) { finish(Outcome.TIMEOUT, -1); return; }
            try {
                BluetoothAdapter adapter = adapter();
                if (adapter == null || !adapter.isEnabled()) { finish(Outcome.BLUETOOTH_UNAVAILABLE, -1); return; }
                stage = warm ? Stage.WARM_CONNECT : Stage.CONNECT; arm(20000);
                connectionCount++;
                gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
                if (gatt == null) finish(Outcome.DISCONNECTED, -1);
            } catch (SecurityException error) { finish(Outcome.PERMISSION_DENIED, -1); }
            catch (RuntimeException error) { finish(Outcome.PROTOCOL_ERROR, -1); }
        }
        void arm(long millis) {
            main.removeCallbacks(timeout);
            main.postDelayed(timeout, recovery.remaining(SystemClock.elapsedRealtime(), millis));
        }
        void freshScan() {
            if (done || cancelled.get() || stage != Stage.WARM_SETTLE) return;
            if (recovery.remaining(SystemClock.elapsedRealtime(), 90000) == 0) { finish(Outcome.TIMEOUT, -1); return; }
            if (!recovery.settled()) return;
            if (!permission(true)) { finish(Outcome.PERMISSION_DENIED, -1); return; }
            try {
                BluetoothAdapter adapter = adapter();
                if (adapter == null || !adapter.isEnabled() || (scanner = adapter.getBluetoothLeScanner()) == null) {
                    finish(Outcome.BLUETOOTH_UNAVAILABLE, -1); return;
                }
                stage = Stage.FRESH_SCAN; arm(15000); freshScanStarted = SystemClock.elapsedRealtimeNanos();
                scanner.startScan(Collections.singletonList(new ScanFilter.Builder()
                                .setServiceUuid(new ParcelUuid(CameraBleProtocol.SERVICE)).build()),
                        new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), freshCallback);
            } catch (SecurityException error) { finish(Outcome.PERMISSION_DENIED, -1); }
            catch (RuntimeException error) { finish(Outcome.PROTOCOL_ERROR, -1); }
        }
        void freshFound(ScanResult result) {
            if (done || cancelled.get() || stage != Stage.FRESH_SCAN) return;
            if (recovery.remaining(SystemClock.elapsedRealtime(), 90000) == 0) { finish(Outcome.TIMEOUT, -1); return; }
            Seen fresh = advertisement(result);
            if (fresh == null || result.getTimestampNanos() < freshScanStarted) return;
            boolean same = device.equals(result.getDevice()) && selectedName.equals(fresh.name);
            if (!recovery.advertisement(same, (fresh.flags & 1) != 0)) {
                finish(Outcome.UNSUPPORTED_CAMERA, -1, Reason.FRESH_ADVERTISEMENT); return;
            }
            freshAsicOn = true;
            stage = Stage.CONNECT; // Retire scan callbacks before stopping its resource.
            stopFreshScan();
            if (done) return;
            advertisementFlags = fresh.flags;
            connect(false);
        }
        void stopFreshScan() {
            if (scanner == null) return;
            BluetoothLeScanner closing = scanner; scanner = null;
            try { closing.stopScan(freshCallback); }
            catch (SecurityException error) { finish(Outcome.PERMISSION_DENIED, -1); }
            catch (RuntimeException error) { finish(Outcome.PROTOCOL_ERROR, -1); }
        }
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
                    finish(Outcome.PROTOCOL_ERROR, -1, Reason.SUBSCRIPTION_START); return;
                }
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                if (!gatt.writeDescriptor(descriptor)) finish(Outcome.PROTOCOL_ERROR, -1, Reason.SUBSCRIPTION_START);
            } catch (SecurityException revoked) { finish(Outcome.PERMISSION_DENIED, -1); }
            catch (RuntimeException error) { finish(Outcome.PROTOCOL_ERROR, -1); }
        }
        void send(CameraBleProtocol.Operation next) {
            operation = next; parsed = null; pendingResponse = null;
            try {
                sensitiveFrame = next == CameraBleProtocol.Operation.AUTHENTICATE
                        ? CameraBleProtocol.authenticate(sequence++, passcode)
                        : next == CameraBleProtocol.Operation.WIFI_START ? CameraBleProtocol.wifiStart(sequence++)
                        : CameraBleProtocol.transferWake(sequence++);
            } catch (IllegalArgumentException error) { finish(Outcome.INVALID_INPUT, -1); return; }
            erasePasscode();
            stage = Stage.COMMAND_WRITE; arm(next == CameraBleProtocol.Operation.AUTHENTICATE ? 10000 : 20000);
            command.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            command.setValue(sensitiveFrame);
            if (done || cancelled.get()) return;
            if (next != CameraBleProtocol.Operation.AUTHENTICATE) wakeSent = true;
            try { if (!gatt.writeCharacteristic(command)) finish(Outcome.PROTOCOL_ERROR, -1, Reason.WRITE_START); }
            catch (SecurityException revoked) { finish(Outcome.PERMISSION_DENIED, -1); }
            catch (RuntimeException error) { finish(Outcome.PROTOCOL_ERROR, -1); }
        }
        void notifyValue(BluetoothGatt source, UUID uuid, byte[] value) {
            if (value == null || value.length > CameraBleProtocol.ATT_BYTES) {
                int length = value == null ? -1 : value.length;
                dispatch(() -> { if (current(source) && normalPhase()) {
                    noteNotification(uuid, length);
                    finish(Outcome.PROTOCOL_ERROR, -1, Reason.NOTIFICATION_BOUNDS);
                } }); return;
            }
            byte[] copy = value.clone();
            dispatch(() -> {
                if (!current(source) || !normalPhase()) return;
                noteNotification(uuid, copy.length);
                if (CameraBleProtocol.EVENT.equals(uuid)) return;
                if (CameraBleProtocol.COMMAND.equals(uuid)) {
                    if (operation == null || !CameraBleProtocol.receipt(copy)) finish(Outcome.PROTOCOL_ERROR, -1, Reason.RECEIPT_FORMAT);
                    return;
                }
                if (!CameraBleProtocol.RESPONSE.equals(uuid) || operation == null) { finish(Outcome.PROTOCOL_ERROR, -1, Reason.RESPONSE_ORDER); return; }
                if (stage == Stage.COMMAND_WRITE) {
                    if (pendingResponse != null && !Arrays.equals(pendingResponse, copy)) finish(Outcome.PROTOCOL_ERROR, -1, Reason.RESPONSE_ORDER);
                    else pendingResponse = copy;
                } else if (stage == Stage.RESPONSE) acceptResponse(copy);
                else if (stage != Stage.ACK_WRITE || !Arrays.equals(pendingResponse, copy)) finish(Outcome.PROTOCOL_ERROR, -1, Reason.RESPONSE_ORDER);
            });
        }
        void noteNotification(UUID uuid, int bytes) {
            notificationRole = CameraBleProtocol.COMMAND.equals(uuid) ? NotificationRole.COMMAND
                    : CameraBleProtocol.RESPONSE.equals(uuid) ? NotificationRole.RESPONSE
                    : CameraBleProtocol.EVENT.equals(uuid) ? NotificationRole.EVENT : NotificationRole.OTHER;
            notificationBytes = bytes;
        }
        void acceptResponse(byte[] frame) {
            try { parsed = CameraBleProtocol.response(frame, operation); }
            catch (IllegalArgumentException error) { finish(Outcome.PROTOCOL_ERROR, -1, Reason.RESPONSE_FORMAT); return; }
            pendingResponse = frame; stage = Stage.ACK_WRITE;
            response.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            response.setValue(parsed.acknowledgement());
            if (done || cancelled.get()) return;
            try { if (!gatt.writeCharacteristic(response)) finish(Outcome.PROTOCOL_ERROR, -1, Reason.ACK_WRITE_START); }
            catch (SecurityException revoked) { finish(Outcome.PERMISSION_DENIED, -1); }
            catch (RuntimeException error) { finish(Outcome.PROTOCOL_ERROR, -1); }
        }
        void completeOperation() {
            if (!parsed.accepted(operation)) {
                finish(operation == CameraBleProtocol.Operation.AUTHENTICATE ? Outcome.AUTH_REJECTED : Outcome.PROTOCOL_ERROR, parsed.status, Reason.CAMERA_REJECTED);
            } else if (operation == CameraBleProtocol.Operation.AUTHENTICATE) {
                CameraBleProtocol.Operation next = CameraBleProtocol.afterAuthentication(advertisementFlags, parsed);
                if (next == null) finish(Outcome.LOCATION_MODE_UNVERIFIED, parsed.status);
                else send(next);
            } else finish(Outcome.PROTOCOL_ACCEPTED, parsed.status);
        }
        boolean normalPhase() {
            return stage == Stage.SUBSCRIBE || stage == Stage.COMMAND_WRITE || stage == Stage.RESPONSE || stage == Stage.ACK_WRITE;
        }
        boolean closeWarmGatt() {
            BluetoothGatt closing = gatt; gatt = null;
            try { closing.close(); return true; }
            catch (SecurityException error) { finish(Outcome.PERMISSION_DENIED, -1); }
            catch (RuntimeException error) { finish(Outcome.PROTOCOL_ERROR, -1); }
            return false;
        }
        // Validate UTF-8 size without materializing an authentication frame during warm recovery.
        boolean validPasscode() {
            if (passcode == null || passcode.length == 0 || passcode.length > CameraBleProtocol.ATT_BYTES - 8) return false;
            int bytes = 0;
            for (int i = 0; i < passcode.length; i++) {
                char c = passcode[i];
                if (Character.isHighSurrogate(c)) {
                    if (++i == passcode.length || !Character.isLowSurrogate(passcode[i])) return false;
                    bytes += 4;
                } else if (Character.isLowSurrogate(c)) return false;
                else bytes += c < 0x80 ? 1 : c < 0x800 ? 2 : 3;
            }
            return bytes <= CameraBleProtocol.ATT_BYTES - 8;
        }
        void erasePasscode() { if (passcode != null) { Arrays.fill(passcode, '\0'); passcode = null; } }
        void eraseFrame() {
            if (sensitiveFrame != null) { Arrays.fill(sensitiveFrame, (byte) 0); sensitiveFrame = null; }
            if (command != null) command.setValue(new byte[0]);
        }
        void finish(Outcome outcome, int status) {
            finish(outcome, status, Reason.STATE);
        }
        void finish(Outcome outcome, int status, Reason reason) {
            if (done) return; done = true; recovery.finish(); main.removeCallbacks(timeout); main.removeCallbacks(settled);
            if (activeWake == this) activeWake = null;
            erasePasscode(); eraseFrame(); pendingResponse = null; selectedName = null; stopFreshScan();
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
            // One bounded, local result. Never include names, addresses, frames, inputs or exception text.
            boolean saved = context.getSharedPreferences("camera_ble_diagnostics", Context.MODE_PRIVATE).edit().clear()
                    .putString("outcome", outcome.name()).putString("reason", reason.name())
                    .putString("stage", stage == null ? "NONE" : stage.name())
                    .putString("operation", operation == null ? "NONE" : operation.name())
                    .putInt("subscription", subscription).putInt("gatt_status", gattStatus)
                    .putString("notification_role", notificationRole.name()).putInt("notification_bytes", notificationBytes)
                    .putInt("camera_status", status).putBoolean("wake_sent", wakeSent)
                    .putBoolean("warm_connected", warmConnected).putBoolean("warm_disconnected", warmDisconnected)
                    .putBoolean("fresh_asic_on", freshAsicOn).putInt("connection_count", connectionCount)
                    .putLong("elapsed_ms", SystemClock.elapsedRealtime() - started).commit();
            android.util.Log.i("LoupeCameraBle", "diagnostic_saved=" + saved);
            observer.onFinished(new Result(outcome, status, wakeSent));
        }
        @Override public void close() { cancelled.set(true); dispatch(() -> finish(Outcome.CANCELLED, -1)); }
    }
}
