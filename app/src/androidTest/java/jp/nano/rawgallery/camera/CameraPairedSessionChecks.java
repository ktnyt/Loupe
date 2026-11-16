package jp.nano.rawgallery.camera;

import android.net.NetworkCapabilities;
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
            return "PASS camera-paired-session request=true exactSsid=true localOnly=true security=true budget=true";
        } finally { Arrays.fill(passphrase, '\0'); }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
