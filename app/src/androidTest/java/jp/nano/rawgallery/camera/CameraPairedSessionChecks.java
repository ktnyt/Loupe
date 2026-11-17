package jp.nano.rawgallery.camera;

import android.net.NetworkCapabilities;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Parcel;
import android.os.SystemClock;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;
import java.io.IOException;
import java.util.Arrays;

/** Request contract checks with synthetic credentials; never scans or connects to user cameras. */
public final class CameraPairedSessionChecks {
    public static String run() throws Exception {
        ImportProgress.INSTANCE.connecting("synthetic");
        ImportProgress.INSTANCE.requestStop();
        ImportProgress.INSTANCE.finish("synthetic");
        ImportProgress.INSTANCE.handoffConnection("synthetic handoff");
        require(ImportProgress.INSTANCE.getStopRequested(), "Handoff preserves cancellation");
        require(ImportProgress.INSTANCE.getActive(), "Handoff remains visible");
        ImportProgress.INSTANCE.connecting("synthetic reset");
        ImportProgress.INSTANCE.finish("synthetic done");
        char[] passphrase = "synthetic-only-passphrase".toCharArray();
        try {
            NetworkRequest wpa2 = CameraPairedSession.request("EXACT-CAMERA", 1, passphrase);
            require(wpa2.hasTransport(NetworkCapabilities.TRANSPORT_WIFI), "Wi-Fi transport");
            require(!wpa2.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET), "Local-only request");
            require(wpa2.getNetworkSpecifier().equals(new WifiNetworkSpecifier.Builder()
                    .setSsid("EXACT-CAMERA").setWpa2Passphrase(new String(passphrase)).build()), "Exact SSID and WPA2");
            require(!wpa2.getNetworkSpecifier().equals(new WifiNetworkSpecifier.Builder()
                    .setSsid("EXACT-CAMERA-OTHER").setWpa2Passphrase(new String(passphrase)).build()), "No prefix match");
            NetworkRequest wpa3 = CameraPairedSession.request("EXACT-CAMERA", 2, passphrase);
            require(wpa3.getNetworkSpecifier().equals(new WifiNetworkSpecifier.Builder()
                    .setSsid("EXACT-CAMERA").setWpa3Passphrase(new String(passphrase)).build()), "WPA3 retained");
            require(!wpa2.equals(wpa3), "No WPA3 downgrade");
            for (int security : new int[]{0, 3, -1}) {
                try { CameraPairedSession.request("EXACT-CAMERA", security, passphrase); throw new AssertionError("Unsupported security accepted"); }
                catch (IOException expected) { }
            }
            try { CameraPairedSession.request(null, 1, passphrase); throw new AssertionError("Incomplete registration accepted"); }
            catch (IOException expected) { }
            require(CameraPairedSession.NETWORK_TIMEOUT_MS == 180000, "Bounded acquisition budget");
            networkEligibility();
            recovery(wpa2);
            return "PASS camera-paired-session request=true exactSsid=true localOnly=true security=true budget=true ownedRecovery=true consent=true cleanup=true isolation=true";
        } finally { Arrays.fill(passphrase, '\0'); }
    }
    private static void networkEligibility() throws Exception {
        require(CameraWifiNetwork.matchesRegisteredSsid("EXACT-CAMERA", "\"EXACT-CAMERA\""), "One quote pair normalized");
        for (String value : new String[]{null, "", "<unknown ssid>", "\"<unknown ssid>\"", "exact-camera", "EXACT-CAMERA-OTHER", "\"\"EXACT-CAMERA\"\""})
            require(!CameraWifiNetwork.matchesRegisteredSsid("EXACT-CAMERA", value), "Unknown, different or extra quotes rejected");
        int ip = 192 | (168 << 8) | (2 << 24);
        require(CameraWifiNetwork.matchesAddress(InetAddress.getByName("192.168.0.2"), ip), "Exact IPv4 binding");
        require(!CameraWifiNetwork.matchesAddress(InetAddress.getByName("192.168.0.2"), 0), "Zero IPv4 rejected");
        require(!CameraWifiNetwork.matchesAddress(InetAddress.getByName("192.168.0.3"), ip), "IPv4 mismatch rejected");
        require(!CameraWifiNetwork.matchesAddress(InetAddress.getByName("::1"), ip), "IPv6 cannot satisfy IPv4 binding");
        require(!CameraWifiNetwork.registeredAddress(null, null, ip), "Unknown network rejected");
    }
    private static Network syntheticNetwork(int id) {
        Parcel parcel = Parcel.obtain();
        try { parcel.writeInt(id); parcel.setDataPosition(0); return Network.CREATOR.createFromParcel(parcel); }
        finally { parcel.recycle(); }
    }
    private static final class FakeAccess implements CameraPairedSession.NetworkAccess {
        final Network requested;
        final int mode;
        final AtomicBoolean cancelled;
        ConnectivityManager.NetworkCallback callback;
        int released, opens;
        boolean disconnected;
        FakeAccess(int index, int mode, AtomicBoolean cancelled) {
            requested = syntheticNetwork(200 + index); this.mode = mode; this.cancelled = cancelled;
        }
        public void request(NetworkRequest request, ConnectivityManager.NetworkCallback value, int timeout) {
            callback = value;
            // register() has not posted its expiry runnable while request() is executing.
            // Deliver after the budget here to reproduce a late callback before that timer.
            if (mode == 7) SystemClock.sleep(timeout + 20L);
            if (mode == 3) callback.onUnavailable();
            else if (mode != 4) callback.onAvailable(requested);
        }
        public void release(ConnectivityManager.NetworkCallback value) {
            require(value == callback, "Release own callback"); released++;
        }
        public HttpURLConnection open(Network network, URL url) {
            require(network.equals(requested), "HTTP bound to newly requested network"); opens++;
            return new HttpURLConnection(url) {
                public void connect() { }
                public boolean usingProxy() { return false; }
                public void disconnect() { disconnected = true; }
                public int getResponseCode() throws IOException {
                    if (mode == 1) throw new SocketTimeoutException("synthetic probe timeout");
                    if (mode == 2) { callback.onLost(requested); throw new IOException("synthetic loss"); }
                    if (mode == 5) { cancelled.set(true); throw new InterruptedIOException("synthetic cancellation"); }
                    if (mode == 6) { Thread.currentThread().interrupt(); throw new InterruptedIOException("synthetic interruption"); }
                    return 200;
                }
                public InputStream getInputStream() { return new ByteArrayInputStream("VER_100\n".getBytes(StandardCharsets.UTF_8)); }
            };
        }
    }
    private static void recovery(NetworkRequest request) throws Exception {
        for (int mode : new int[]{0, 1, 2, 3, 4, 5, 6, 7}) {
            AtomicBoolean cancelled = new AtomicBoolean();
            List<FakeAccess> attempts = new ArrayList<>();
            int[] ble = {0};
            try {
                CameraPairedSession result = CameraPairedSession.connectWithRecovery(true, () -> {
                    FakeAccess access = new FakeAccess(attempts.size(), attempts.isEmpty() ? mode : 0, cancelled);
                    attempts.add(access);
                    return new CameraPairedSession(access, cancelled::get);
                }, (session, wake, budget) -> {
                    if (wake) {
                        ble[0]++;
                        require(attempts.get(0).released == 1, "Tentative released before BLE fallback");
                        require(attempts.get(0).disconnected, "Tentative HTTP disconnected before fallback");
                    }
                    session.acquire(request, value -> { }, (mode == 4 || mode == 7) && !wake ? 100 : budget);
                });
                require(mode <= 2, "Refusal, pre-acquisition deadline and cancellation must end");
                require(attempts.size() == (mode == 0 ? 1 : 2), "Fresh fallback session count");
                require(ble[0] == (mode == 0 ? 0 : 1), "Eligible success bypasses BLE");
                FakeAccess owned = attempts.get(attempts.size() - 1);
                require(result.network.equals(owned.requested) && owned.released == 0, "Owned request retained through import");
                if (mode > 0) {
                    FakeAccess stale = attempts.get(0);
                    stale.callback.onUnavailable(); stale.callback.onLost(stale.requested); stale.callback.onAvailable(syntheticNetwork(900));
                    require(!result.isCancelled() && result.network.equals(owned.requested), "Late callbacks isolated");
                }
                owned.callback.onLost(owned.requested);
                require(result.isCancelled(), "Import network loss detected");
                result.close(); result.close();
                require(owned.released == 1, "Close releases exactly once");
            } catch (IOException failure) {
                require(mode >= 3, "Only refusal, pre-acquisition deadline or external cancel end without fallback");
                require(attempts.size() == 1 && ble[0] == 0 && attempts.get(0).released == 1, "No reprompt after refusal, deadline or cancel");
                if (mode == 7) require(attempts.get(0).opens == 0, "Late availability cannot acquire, probe or authorize BLE fallback");
            } finally { if (mode == 6) Thread.interrupted(); }
        }
        // A later explicit import starts independently after the previous import lost its network.
        AtomicBoolean cancelled = new AtomicBoolean();
        FakeAccess first = new FakeAccess(10, 0, cancelled);
        CameraPairedSession previous = new CameraPairedSession(first, cancelled::get);
        previous.acquire(request, value -> { }, CameraPairedSession.NETWORK_TIMEOUT_MS);
        first.callback.onLost(first.requested); previous.close();
        FakeAccess next = new FakeAccess(11, 0, cancelled);
        CameraPairedSession again = CameraPairedSession.connectWithRecovery(true,
                () -> new CameraPairedSession(next, cancelled::get), (session, wake, budget) -> {
                    require(!wake, "Next explicit import can reacquire without BLE"); session.acquire(request, value -> { }, budget);
                });
        require(!again.isCancelled() && again.network.equals(next.requested), "Fresh explicit import succeeds"); again.close();
        FakeAccess normal = new FakeAccess(12, 0, cancelled);
        CameraPairedSession original = CameraPairedSession.connectWithRecovery(false,
                () -> new CameraPairedSession(normal, cancelled::get), (session, wake, budget) -> {
                    require(wake && budget == CameraPairedSession.NETWORK_TIMEOUT_MS, "Ineligible retains original BLE flow");
                    session.acquire(request, value -> { }, budget);
                });
        original.close();
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
