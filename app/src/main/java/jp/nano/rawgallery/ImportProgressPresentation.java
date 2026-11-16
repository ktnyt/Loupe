package jp.nano.rawgallery;

import jp.nano.rawgallery.camera.ImportProgress;

/** One stage vocabulary for the import screen, library band and navigation accessibility. */
public final class ImportProgressPresentation {
  public final String primary, secondary;
  public final int done, total;
  public final boolean rich;

  private ImportProgressPresentation(String primary, String secondary, int done, int total, boolean rich) {
    this.primary = primary;
    this.secondary = secondary;
    this.done = done;
    this.total = total;
    this.rich = rich;
  }

  public String text() { return secondary.isEmpty() ? primary : primary + "\n" + secondary; }
  public String accessibility() { return text().replace('\n', '、'); }

  public static ImportProgressPresentation current() {
    ImportProgress state = ImportProgress.INSTANCE;
    var p = state.getPhoto();
    if (p == null) {
      String label = state.getActive()
          ? state.getWaiting() ? value(state.getStatus(), "カメラ接続待ち")
              : state.getTotal() > 0 ? "カメラから取り込み中" : "カメラを確認しています"
          : value(state.getLastMessage(), "まだ取り込んでいません");
      String count = state.getActive() && !state.getWaiting() && state.getTotal() > 0
          ? count(state.getDone(), state.getTotal()) : "";
      return new ImportProgressPresentation(label, count, state.getDone(), state.getTotal(), false);
    }
    if (state.getActive() && state.getWaiting()) {
      String primary = state.getStopRequested() ? "取り込みを中止しています"
          : value(state.getStatus(), "カメラ接続待ち");
      return new ImportProgressPresentation(primary, summary(p), 0, -1, true);
    }
    String primary;
    String secondary;
    switch (p.getStage()) {
      case LISTING:
        primary = "写真の一覧を確認中";
        secondary = p.getTotal() < 0 ? (p.getProcessed() < 0 ? "総数を確認中" : p.getProcessed() + "件を検出 · 総数を確認中") : count(p.getProcessed(), p.getTotal());
        break;
      case HISTORY:
        primary = "取り込み履歴を確認中";
        secondary = count(p.getProcessed(), p.getTotal());
        if (p.getKnown() >= 0) secondary += " · 保存済み " + p.getKnown() + "件";
        break;
      case CONTENT:
        primary = "原本の内容を確認中";
        secondary = "要確認 " + count(p.getProcessed(), p.getTotal());
        if (p.getKnown() >= 0) secondary += " · 保存済み " + p.getKnown() + "件";
        if (p.getSaved() >= 0) secondary += " · 新規保存 " + p.getSaved() + "件";
        if (p.getSourceBytes() >= 0) secondary += " · 読取 " + bytes(p.getSourceBytes());
        break;
      case SAVING:
        primary = "新しい写真を保存中";
        secondary = p.getSaved() >= 0 ? "新規保存 " + p.getSaved() + "件" : "保存を確認中";
        break;
      default:
        primary = switch (p.getStage()) {
          case COMPLETE -> "取り込み完了";
          case CANCELLED -> "取り込みを中止しました";
          case FAILED -> "取り込みに失敗しました";
          default -> "取り込み終了";
        };
        secondary = summary(p);
        if (p.getStage() == ImportProgress.PhotoStage.FAILED && state.getLastMessage() != null)
          primary += " · " + state.getLastMessage();
        break;
    }
    if (state.getActive() && state.getStopRequested()) primary = "取り込みを中止しています";
    return new ImportProgressPresentation(primary, secondary, p.getProcessed(),
        p.getStage() == ImportProgress.PhotoStage.SAVING ? -1 : p.getTotal(), true);
  }

  private static String summary(ImportProgress.PhotoState p) {
    String text = p.getSaved() >= 0 ? "新規保存 " + p.getSaved() + "件" : "";
    int existing = p.getKnown() < 0 ? p.getContentMatches() : p.getKnown() + Math.max(0, p.getContentMatches());
    if (existing >= 0) text += (text.isEmpty() ? "" : " · ") + "保存済み " + existing + "件";
    if (p.getSourceBytes() >= 0) text += (text.isEmpty() ? "" : " · ") + "読取 " + bytes(p.getSourceBytes());
    return text;
  }

  private static String value(String text, String fallback) { return text == null ? fallback : text; }
  private static String count(int done, int total) { return done < 0 ? "件数を確認中" : total < 0 ? done + "件を確認" : done + " / " + total + "件"; }
  private static String bytes(long bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return String.format(java.util.Locale.JAPAN, "%.1f KiB", bytes / 1024d);
    return String.format(java.util.Locale.JAPAN, "%.1f MiB", bytes / (1024d * 1024));
  }
}
