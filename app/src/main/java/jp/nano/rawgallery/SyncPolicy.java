package jp.nano.rawgallery;

final class SyncPolicy {
  static final long DAY = 86400000L;

  static boolean supported(String model) {
    return model.equals("RICOH GR IV")
        || model.equals("RICOH GR IV Monochrome")
        || model.equals("OM-1MarkII")
        || model.equals("OM-1 Mark II")
        || model.equals("E-P7")
        || model.startsWith("Leitzphone powered by Xiaomi");
  }

  static long secondsToMillis(long seconds) {
    return seconds > 0 && seconds <= Long.MAX_VALUE / 1000 ? seconds * 1000 : 0;
  }

  static long retentionDate(long taken, long added, long created, boolean jpeg) {
    if (taken > 0) return taken;
    if (!jpeg) return 0;
    return added > 0 ? added : Math.max(0, created);
  }

  static boolean eligible(
      long taken,
      long added,
      long created,
      boolean jpeg,
      long now,
      int days,
      boolean verified,
      boolean writable,
      boolean previewSaved) {
    long date = retentionDate(taken, added, created, jpeg);
    if (jpeg && date == 0) return days >= 1 && days <= 365 && verified && writable && previewSaved;
    return eligible(date, now, days, verified, writable, previewSaved);
  }

  static boolean eligible(
      long taken, long now, int days, boolean verified, boolean writable, boolean previewSaved) {
    return days >= 1
        && days <= 365
        && taken > 0
        && taken <= now
        && now - taken >= days * DAY
        && verified
        && writable
        && previewSaved;
  }
}
