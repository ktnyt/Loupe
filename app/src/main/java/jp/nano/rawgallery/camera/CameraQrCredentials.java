package jp.nano.rawgallery.camera;

import java.util.Arrays;

/** Bounded camera-QR protocol facts. No raw QR or credential is logged; secret arrays are owned until close. */
public final class CameraQrCredentials implements AutoCloseable {
    public final String ssid, bleName;
    public final int wifiSecurity;
    private char[] code, wifi;

    private CameraQrCredentials(String ssid, String bleName, int wifiSecurity, char[] code, char[] wifi) {
        this.ssid = ssid; this.bleName = bleName; this.wifiSecurity = wifiSecurity; this.code = code; this.wifi = wifi;
    }
    public static CameraQrCredentials parse(String raw) {
        if (raw == null || raw.length() > 512) throw invalid();
        String[] fields = raw.split(",", -1);
        int ssidAt;
        if (fields.length == 6 && fields[0].equals("OIS2") && fields[1].equals("3")) ssidAt = 2;
        else if (fields.length == 7 && fields[0].equals("OIS3") && fields[1].equals("3")
                && (fields[2].equals("1") || fields[2].equals("2"))) ssidAt = 3;
        else throw invalid();
        char[] ssid = null, wifi = null, name = null, pass = null;
        try {
            ssid = decode(fields[ssidAt]); wifi = decode(fields[ssidAt + 1]);
            name = decode(fields[ssidAt + 2]); pass = decode(fields[ssidAt + 3]);
            if (!bounded(ssid, 1, 32) || !bounded(wifi, 8, 63) || !bounded(name, 1, 32)
                    || !bounded(pass, 1, 12)) throw invalid();
            CameraQrCredentials result = new CameraQrCredentials(new String(ssid), new String(name),
                    ssidAt == 2 ? 1 : Integer.parseInt(fields[2]), pass, wifi);
            pass = null; wifi = null;
            return result;
        } finally {
            clear(ssid); clear(wifi); clear(name); clear(pass);
        }
    }
    private static boolean bounded(char[] value, int min, int max) {
        if (value.length < min || value.length > max) return false;
        boolean nonblank = false;
        for (char c : value) {
            if (c < 0x20 || c > 0x7e) return false;
            if (c != ' ') nonblank = true;
        }
        return nonblank;
    }
    private static char[] decode(String value) {
        char[] result = new char[value.length()];
        for (int i = 0; i < result.length; i++) {
            char c = value.charAt(i), converted;
            if (c >= 'A' && c <= 'V') converted = (char) ('V' - (c - 'A'));
            else if (c >= 'a' && c <= 'z') converted = (char) ('z' - (c - 'a'));
            else {
                String from = "0123456789WXYZ/-+*%$ ";
                String to =   "/-+*%$ZYXW9876012345 ";
                int position = from.indexOf(c);
                if (position < 0) { clear(result); throw invalid(); }
                converted = to.charAt(position);
            }
            result[i] = converted;
        }
        return result;
    }
    public synchronized char[] passcode() {
        if (code == null) throw new IllegalStateException("Camera credential is closed");
        return code.clone();
    }
    public synchronized char[] wifiPassphrase() {
        if (wifi == null) throw new IllegalStateException("Camera credential is closed");
        return wifi.clone();
    }
    @Override public synchronized void close() { clear(code); clear(wifi); code = null; wifi = null; }
    private static void clear(char[] value) { if (value != null) Arrays.fill(value, '\0'); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Unsupported camera QR"); }
}
