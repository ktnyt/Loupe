package jp.nano.rawgallery;

public final class SyncPolicyTest {
  public static void main(String[] args) {
    long now = 100 * SyncPolicy.DAY;
    check(false, 0, now, 7, true, true, true);
    check(false, now + 1, now, 7, true, true, true);
    check(false, now - 7 * SyncPolicy.DAY + 1, now, 7, true, true, true);
    check(true, now - 7 * SyncPolicy.DAY, now, 7, true, true, true);
    check(true, now - 8 * SyncPolicy.DAY, now, 7, true, true, true);
    check(false, now - 8 * SyncPolicy.DAY, now, 7, false, true, true);
    check(false, now - 8 * SyncPolicy.DAY, now, 7, true, false, true);
    check(false, now - 8 * SyncPolicy.DAY, now, 7, true, true, false);
    check(false, now - 8 * SyncPolicy.DAY, now, 0, true, true, true);
    check(false, now - 8 * SyncPolicy.DAY, now, 366, true, true, true);
    check(false, now - 8 * SyncPolicy.DAY, now, 30, true, true, true);
    for (String camera :
        new String[] {
          "RICOH GR IV",
          "RICOH GR IV Monochrome",
          "OM-1MarkII",
          "E-P7",
          "Leitzphone powered by Xiaomi"
        }) if (!SyncPolicy.supported(camera)) throw new AssertionError(camera);
    for (String camera : new String[] {"RICOH GR III", "OM-1", "E-P5", "Unknown"})
      if (SyncPolicy.supported(camera)) throw new AssertionError(camera);
    if (RawPreview.captureTime("2026:02:30 10:00:00", "") != 0
        || RawPreview.captureTime("", "") != 0)
      throw new AssertionError("Invalid capture date accepted");
    if (RawPreview.captureTime("2026:10:21 12:00:00", "+09:00")
        != java.time.Instant.parse("2026-10-21T03:00:00Z").toEpochMilli())
      throw new AssertionError("Capture offset incorrect");
    if (RawPreview.captureTime("2026:10:21 12:00:00", null) <= 0)
      throw new AssertionError("Missing offset must use device timezone");
    if (SyncPolicy.retentionDate(30, 20, 10, true) != 30
        || SyncPolicy.retentionDate(0, 20, 10, true) != 20
        || SyncPolicy.retentionDate(0, 0, 10, true) != 10
        || SyncPolicy.retentionDate(0, 20, 10, false) != 0)
      throw new AssertionError("Date priority changed");
    for (int flags = 0; flags < 8; flags++) {
      boolean actual =
          SyncPolicy.eligible(
              0, 0, 0, true, now, 7, (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0);
      if (actual != (flags == 7)) throw new AssertionError("Undated JPEG safety gate");
    }
    if (SyncPolicy.eligible(0, now, 1, true, now, 7, true, true, true)
        || SyncPolicy.eligible(now + 1, 1, 1, true, now, 7, true, true, true)
        || SyncPolicy.eligible(0, 0, 0, false, now, 7, true, true, true)
        || !SyncPolicy.eligible(0, now - 7 * SyncPolicy.DAY, 0, true, now, 7, true, true, true)
        || !SyncPolicy.eligible(0, 0, now - 7 * SyncPolicy.DAY, true, now, 7, true, true, true))
      throw new AssertionError("Fallback retention boundary");
    if (SyncPolicy.secondsToMillis(Long.MAX_VALUE) != 0
        || SyncPolicy.secondsToMillis(-1) != 0
        || SyncPolicy.secondsToMillis(123) != 123000)
      throw new AssertionError("Seconds conversion overflow");
    System.out.println("Retention, camera, capture date: 44 checks passed");
  }

  private static void check(
      boolean expected,
      long taken,
      long now,
      int days,
      boolean verified,
      boolean write,
      boolean preview) {
    if (SyncPolicy.eligible(taken, now, days, verified, write, preview) != expected)
      throw new AssertionError("Unsafe retention decision");
  }
}
