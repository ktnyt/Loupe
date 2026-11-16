package jp.nano.rawgallery.camera;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.net.*;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** One explicit registered-camera operation. Owns its local-only request through the entire import. */
public final class CameraPairedSession implements Closeable {
    public static final class MissingWifiException extends IOException {
        MissingWifiException() { super("Wi-Fi情報がありません。カメラのQRコードをもう一度登録してください"); }
    }
    static final long NETWORK_TIMEOUT_MS = 180000;
    private final ConnectivityManager manager;
    private final BooleanSupplier cancelled;
    private final Handler timer = new Handler(Looper.getMainLooper());
    private final CountDownLatch available = new CountDownLatch(1);
    private final AtomicReference<HttpURLConnection> connection = new AtomicReference<>();
    private volatile Network selected;
    private volatile boolean closed, lost, unavailable, acquiring = true;
    private boolean registered;
    private long deadline;
    private final Runnable expired = () -> { if (acquiring) { unavailable = true; available.countDown(); abortHttp(); } };
    public Network network;
    public Ep7WifiClient client;
    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network value) {
            synchronized (CameraPairedSession.this) {
                if (closed || unavailable || selected != null) return;
                selected = value;
            }
            available.countDown();
        }
        @Override public void onUnavailable() { if (!closed) { unavailable = true; available.countDown(); abortHttp(); } }
        @Override public void onLost(Network value) {
            if (!closed && value.equals(selected)) { lost = true; available.countDown(); abortHttp(); }
        }
    };
    private CameraPairedSession(Context context, BooleanSupplier cancelled) {
        this.manager = context.getSystemService(ConnectivityManager.class);
        this.cancelled = cancelled;
    }
    /** Must run off main. Profile IDs, never credentials, are passed from the visible Activity. */
    public static CameraPairedSession connect(Context context, String id, BooleanSupplier cancelled,
            Consumer<String> progress) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Connect off main");
        CameraPairedSession session = new CameraPairedSession(context, cancelled);
        try (CameraCredentialStore.Registration registration = new CameraCredentialStore(context).registration(id)) {
            if (registration == null || !registration.hasWifi()) throw new MissingWifiException();
            progress.accept("登録したカメラを探しています");
            CameraBleWake helper = new CameraBleWake(context);
            BluetoothDevice device = find(helper, registration.bleName, session);
            progress.accept("カメラを起動しています");
            CountDownLatch accepted = new CountDownLatch(1);
            AtomicReference<CameraBleWake.Result> result = new AtomicReference<>();
            char[] code = registration.passcode();
            Closeable wake;
            try { wake = helper.wakeForWifi(device, code, value -> { result.set(value); accepted.countDown(); }); }
            finally { Arrays.fill(code, '\0'); }
            try (Closeable heldWake = wake) {
                session.await(accepted, SystemClock.elapsedRealtime() + 95000);
                CameraBleWake.Result outcome = result.get();
                if (outcome == null || outcome.outcome != CameraBleWake.Outcome.PROTOCOL_ACCEPTED)
                    throw new IOException("カメラを起動できませんでした。Bluetoothの権限とカメラを確認してください");
                session.pause(outcome.wifiStart ? 5500 : 3000);
                progress.accept("カメラのWi-Fiに接続しています。Androidの接続確認を許可してください");
                char[] phrase = registration.wifiPassphrase();
                NetworkRequest request;
                try { request = request(registration.ssid, registration.wifiSecurity, phrase); }
                finally { Arrays.fill(phrase, '\0'); }
                session.deadline = SystemClock.elapsedRealtime() + NETWORK_TIMEOUT_MS;
                session.register(request);
                session.await(session.available, session.deadline);
                session.check();
                if (session.selected == null) throw new IOException("カメラのWi-Fiに接続できませんでした");
                session.network = session.selected;
                session.client = new Ep7WifiClient(url -> {
                    session.check();
                    HttpURLConnection current = (HttpURLConnection)session.network.openConnection(url, Proxy.NO_PROXY);
                    session.connection.set(current);
                    try { session.check(); return current; }
                    catch (IOException failure) { current.disconnect(); throw failure; }
                }, session::isCancelled);
                progress.accept("カメラへの接続を確認しています");
                session.client.probe();
                session.check();
                session.acquiring = false;
                session.timer.removeCallbacks(session.expired);
                // Retained Bluetooth is released after the exact requested network passes VER_100.
            }
            return session;
        } catch (SecurityException failure) {
            session.close(); throw new IOException("BluetoothとWi-Fiの接続権限を許可してください", failure);
        } catch (IOException | RuntimeException failure) { session.close(); throw failure; }
    }
    static NetworkRequest request(String ssid, int security, char[] phrase) throws IOException {
        if (ssid == null || phrase == null) throw new IOException("Wi-Fi registration incomplete");
        WifiNetworkSpecifier.Builder specifier = new WifiNetworkSpecifier.Builder().setSsid(ssid);
        // The platform requires a temporary String; no persistent OS configuration is created.
        String passphrase = new String(phrase);
        if (security == 1) specifier.setWpa2Passphrase(passphrase);
        else if (security == 2) specifier.setWpa3Passphrase(passphrase);
        else throw new IOException("Unsupported registered Wi-Fi security");
        return new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier.build()).build();
    }
    private synchronized void register(NetworkRequest request) throws IOException {
        check();
        if (manager == null) throw new IOException("Wi-Fi unavailable");
        manager.requestNetwork(request, callback, (int)NETWORK_TIMEOUT_MS);
        registered = true;
        timer.postDelayed(expired, Math.max(1, deadline - SystemClock.elapsedRealtime()));
    }
    private static BluetoothDevice find(CameraBleWake helper, String exactName, CameraPairedSession session) throws IOException {
        CountDownLatch found = new CountDownLatch(1);
        AtomicReference<BluetoothDevice> selected = new AtomicReference<>();
        try (Closeable scan = helper.scan(new CameraBleWake.ScanObserver() {
            public void onCamera(BluetoothDevice device, String name) { if (exactName.equals(name)) { selected.compareAndSet(null, device); found.countDown(); } }
            public void onFinished() { found.countDown(); }
            public void onError(CameraBleWake.Outcome outcome) { found.countDown(); }
        })) {
            session.await(found, SystemClock.elapsedRealtime() + 16000);
            if (selected.get() == null) throw new IOException("登録したカメラが見つかりません。Bluetoothとカメラを確認してください");
            return selected.get();
        }
    }
    private void await(CountDownLatch signal, long until) throws IOException {
        while (true) {
            check();
            long remaining = until - SystemClock.elapsedRealtime();
            if (remaining <= 0) throw new IOException("カメラへの接続が時間切れになりました");
            try { if (signal.await(Math.min(100, remaining), TimeUnit.MILLISECONDS)) { check(); return; } }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new InterruptedIOException("Cancelled"); }
        }
    }
    private void pause(long duration) throws IOException {
        long end = SystemClock.elapsedRealtime() + duration;
        while (SystemClock.elapsedRealtime() < end) {
            check();
            try { Thread.sleep(Math.min(100, Math.max(1, end - SystemClock.elapsedRealtime()))); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new InterruptedIOException("Cancelled"); }
        }
    }
    public boolean isCancelled() {
        return closed || lost || unavailable || cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()
                || (acquiring && deadline > 0 && SystemClock.elapsedRealtime() >= deadline);
    }
    private void check() throws IOException {
        if (lost) throw new IOException("カメラとのWi-Fi接続が切れました");
        if (unavailable || (acquiring && deadline > 0 && SystemClock.elapsedRealtime() >= deadline))
            throw new IOException("カメラのWi-Fiに接続できませんでした");
        if (closed || cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled");
    }
    private void abortHttp() { HttpURLConnection active = connection.getAndSet(null); if (active != null) active.disconnect(); }
    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            timer.removeCallbacks(expired);
            if (registered) {
                registered = false;
                try { manager.unregisterNetworkCallback(callback); }
                catch (RuntimeException ignored) { /* Permission revocation and system removal also end the request. */ }
            }
        }
        available.countDown();
        abortHttp();
    }
}
