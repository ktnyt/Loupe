import jp.nano.rawgallery.camera.CameraQrCredentials;
import java.util.Arrays;

public final class CameraQrCredentialsTest {
    private static final String VECTOR = "OIS2,3,TVJ1CRDC,-+*%$ZYX,UKR1CRDC,-+*%$Z";
    public static void main(String[] args) {
        for (String vector : new String[]{VECTOR, VECTOR.replace("OIS2,3,", "OIS3,3,1,"),
                VECTOR.replace("OIS2,3,", "OIS3,3,2,")}) {
            try (CameraQrCredentials parsed = CameraQrCredentials.parse(vector)) {
                require(parsed.ssid.equals("CAM-TEST") && parsed.bleName.equals("BLE-TEST"), "QR identity");
                char[] code = parsed.passcode(); require(Arrays.equals(code, "123456".toCharArray()), "BLE code");
                Arrays.fill(code, '\0');
                require(Arrays.equals(parsed.passcode(), "123456".toCharArray()), "Caller owns a copy");
            }
        }
        String[] invalid = {null, "", "WIFI:S:CAM;P:password;;", VECTOR.replace(",3,", ",2,"),
                VECTOR.replace("OIS2,3,", "OIS3,3,9,"), VECTOR + ",extra", VECTOR.substring(0, VECTOR.lastIndexOf(',')),
                VECTOR.replace("UKR1CRDC", ""), VECTOR.replace("UKR1CRDC", "_"),
                VECTOR.substring(0, VECTOR.lastIndexOf(',') + 1),
                VECTOR.substring(0, VECTOR.lastIndexOf(',') + 1) + "-".repeat(13), "X".repeat(513)};
        for (String value : invalid) {
            try { CameraQrCredentials.parse(value); throw new AssertionError("Malformed QR accepted"); }
            catch (IllegalArgumentException expected) { require(!expected.getMessage().contains("OIS2"), "No input in errors"); }
        }
        CameraQrCredentials closed = CameraQrCredentials.parse(VECTOR); closed.close();
        try { closed.passcode(); throw new AssertionError("Closed credential reused"); }
        catch (IllegalStateException expected) { }
        System.out.println("PASS camera QR fixed vectors, strict fields/versions/bounds, owned passcode and close");
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
