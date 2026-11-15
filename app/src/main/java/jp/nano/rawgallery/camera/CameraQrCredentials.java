package jp.nano.rawgallery.camera;

import java.util.Arrays;

/** Bounded camera-QR protocol facts. No raw QR, Wi-Fi password or credential is logged or retained. */
public final class CameraQrCredentials implements AutoCloseable {
    public final String ssid, bleName;
    private char[] code;

    private CameraQrCredentials(String ssid, String bleName, char[] code) {
        this.ssid = ssid; this.bleName = bleName; this.code = code;
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
            if (!bounded(ssid, 1, 32) || !bounded(wifi, 1, 64) || !bounded(name, 1, 32)
                    || !bounded(pass, 1, 12)) throw invalid();
            CameraQrCredentials result = new CameraQrCredentials(new String(ssid), new String(name), pass);
            pass = null;
            return result;
        } finally {
            clear(ssid); clear(wifi); clear(name); clear(pass);
        }
    }
    private static boolean bounded(char[] value, int min, int max) {
        if (value.length < min || value.length > max) return false;
        for (char c : value) if (c != ' ') return true;
        return false;
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
    @Override public synchronized void close() { clear(code); code = null; }
    private static void clear(char[] value) { if (value != null) Arrays.fill(value, '\0'); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Unsupported camera QR"); }
}
