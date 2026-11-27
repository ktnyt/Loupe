package jp.nano.rawgallery;

import static jp.nano.rawgallery.AlbumTagEntryChecks.*;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;

/** Synthetic ranking rows and the normal sort entry. */
final class AffordanceChecks {
  static String run(Instrumentation t) throws Exception {
    String regression = SortNamingChecks.run(t);
    MainActivity a =
        (MainActivity)
            t.startActivitySync(
                new Intent(t.getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    int count = 0;
    try {
      for (int width : new int[] {320, 400})
        for (float scale : new float[] {1f, 1.3f})
          for (boolean longer : new boolean[] {false, true}) {
            LinearLayout[] content = {null};
            main(
                t,
                () -> {
                  Configuration cfg = new Configuration(a.getResources().getConfiguration());
                  cfg.fontScale = scale;
                  Context c =
                      new android.view.ContextThemeWrapper(
                          a.createConfigurationContext(cfg), R.style.AppTheme);
                  LinearLayout root = new LinearLayout(a);
                  root.setOrientation(1);
                  root.setBackgroundColor(GalleryUi.BG);
                  root.setPadding(0, GalleryUi.dp(a, 64), 0, 0);
                  content[0] = new LinearLayout(c);
                  content[0].setOrientation(1);
                  content[0].setPadding(GalleryUi.dp(c, 8), 0, GalleryUi.dp(c, 8), 0);
                  for (int i = 0; i < 3; i++) {
                    String name =
                        longer
                            ? new String[] {
                                  "P1010403_嵐山_渡月橋_夕景_連写_0001_編集用.ORF",
                                  "P1010404_嵐山_渡月橋_夕景_連写_0002_編集用.ORF",
                                  "fixture-3-with-a-very-long-file-name-for-wrapping-check.jpg"
                                }
                                [i]
                            : "fixture-" + (i + 1) + ".jpg";
                    RankingResultRow row =
                        new RankingResultRow(c, (i == 0 ? "1位" : "2位") + " · " + name);
                    if (i == 1) row.previewFailed();
                    else {
                      Bitmap b = Bitmap.createBitmap(88, 72, Bitmap.Config.ARGB_8888);
                      new Canvas(b).drawColor(i == 0 ? 0xff587c8c : 0xff688664);
                      row.thumbnail.setImageBitmap(b);
                    }
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                    lp.bottomMargin = GalleryUi.dp(c, 4);
                    content[0].addView(row, lp);
                  }
                  root.addView(
                      content[0], new LinearLayout.LayoutParams(GalleryUi.dp(c, width), -2));
                  a.setContentView(root);
                  return true;
                });
            waitFor(t, "fixture layout", () -> content[0].getHeight() > 0);
            main(
                t,
                () -> {
                  for (int i = 0; i < 3; i++) {
                    RankingResultRow row = (RankingResultRow) content[0].getChildAt(i);
                    TextView name = (TextView) row.getChildAt(1);
                    if (row.getHeight() < GalleryUi.dp(a, 84)
                        || name.getLayout().getEllipsisCount(0) > 0
                        || name.getLayout().getHeight() > name.getHeight())
                      throw new AssertionError("row bounds");
                    for (int j = 0; j < row.getChildCount(); j++)
                      if (row.getChildAt(j).isClickable() || row.getChildAt(j).isFocusable())
                        throw new AssertionError("nested target");
                    AccessibilityNodeInfo info = row.createAccessibilityNodeInfo();
                    boolean action = false;
                    for (AccessibilityNodeInfo.AccessibilityAction act : info.getActionList())
                      if (android.text.TextUtils.equals("拡大", act.getLabel())) action = true;
                    if (!action || !Button.class.getName().contentEquals(info.getClassName()))
                      throw new AssertionError("row AX");
                  }
                  return true;
                });
            capture(
                t,
                a,
                content[0],
                content[0].getHeight(),
                "affordance-rows-" + width + "-" + scale + "-" + longer);
            count++;
          }
      return regression
          + "PASS "
          + count
          + " synthetic ranking layouts at 320/400dp and fonts 1/1.3. Short and long filenames, failed preview and the accessibility action checked.\n";
    } finally {
      main(
          t,
          () -> {
            a.finish();
            return true;
          });
    }
  }
}
