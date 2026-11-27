import jp.nano.rawgallery.camera.CameraSourceKey;

public final class CameraSourceKeyTest {
  public static void main(String[] args) {
    String one = CameraSourceKey.wifi("\"E-P7-synthetic\"", "a0:b1:c2:d3:e4:f5");
    if (one == null || !one.equals(CameraSourceKey.wifi("E-P7-synthetic", "A0:B1:C2:D3:E4:F5")))
      throw new AssertionError("Stable AP key");
    if (one.equals(CameraSourceKey.wifi("Other camera", "a0:b1:c2:d3:e4:f5"))
        || one.equals(CameraSourceKey.wifi("E-P7-synthetic", "a0:b1:c2:d3:e4:f6")))
      throw new AssertionError("Different AP identified as same source");
    for (String mac :
        new String[] {"02:00:00:00:00:00", "00:00:00:00:00:00", "ff:ff:ff:ff:ff:ff", "bad"})
      if (CameraSourceKey.wifi("camera", mac) != null)
        throw new AssertionError("Redacted or invalid BSSID");
    if (CameraSourceKey.wifi("<unknown ssid>", "a0:b1:c2:d3:e4:f5") != null
        || CameraSourceKey.sd("0000-0000", 100) != null
        || CameraSourceKey.sd("primary", 100) != null
        || CameraSourceKey.sd("1234-ABCD", 0) != null)
      throw new AssertionError("Unstable source accepted");
    String card = CameraSourceKey.sd("1234-ABCD", 100);
    if (card == null
        || !card.equals(CameraSourceKey.sd("1234abcd", 100))
        || card.equals(CameraSourceKey.sd("1234-ABCD", 200))
        || card.equals(CameraSourceKey.sd("5678-ABCD", 100)))
      throw new AssertionError("Card serial/geometry separation");
    if (one.contains("E-P7") || card.contains("1234"))
      throw new AssertionError("Source metadata leaked into key");
    System.out.println(
        "PASS source keys: AP identity, redaction rejection, card serial/geometry, and non-plaintext IDs");
  }
}
