package jp.nano.rawgallery;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.*;
import android.widget.*;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Draft choices remain local until Apply; late count results cannot replace newer choices. */
final class PhotoFilterSheet extends BottomSheetDialog {
  interface Count {
    void request(String camera, int rank, IntConsumer result);
  }

  interface RankedCount {
    void request(String camera, int rank, Consumer<CountResult> result);
  }

  enum State {
    PENDING,
    FAILED,
    READY
  }

  record CountResult(State state, int count, BooleanSupplier current) {
    static CountResult pending() {
      return new CountResult(State.PENDING, 0, () -> false);
    }

    static CountResult failed() {
      return new CountResult(State.FAILED, 0, () -> false);
    }

    static CountResult ready(int count, BooleanSupplier current) {
      return new CountResult(State.READY, count, current);
    }
  }

  static final String[] CAMERAS = {"すべて", "GR", "OM-1", "E-P7", "Leitz", "その他"};
  static final String[] RANKS = {"すべて", "1位", "順位が未決定", "順位決定済み"};
  private final List<MaterialButton> ranks = new ArrayList<>(), cameras = new ArrayList<>();
  private final MaterialButton apply;
  private final TextView countFailure;
  private final RankedCount count;
  private final Runnable retry;
  private CountResult result = CountResult.pending();
  private int rank, request;
  private String camera;

  PhotoFilterSheet(
      Context c, String camera, int rank, Count count, BiConsumer<String, Integer> committed) {
    this(
        c,
        camera,
        rank,
        (source, order, deliver) ->
            count.request(
                source,
                order,
                value ->
                    deliver.accept(
                        value < 0 ? CountResult.failed() : CountResult.ready(value, () -> true))),
        committed,
        () -> {});
  }

  PhotoFilterSheet(
      Context c,
      String camera,
      int rank,
      RankedCount count,
      BiConsumer<String, Integer> committed,
      Runnable retry) {
    super(c);
    this.camera = camera;
    this.rank = rank;
    this.count = count;
    this.retry = retry;
    ScrollView scroll = new ScrollView(c);
    scroll.setFillViewport(false);
    LinearLayout body = new LinearLayout(c);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(dp(20), dp(12), dp(20), dp(24));
    scroll.addView(body);
    LinearLayout title =
        LibraryUi.toolbar(c, "絞り込み", LibraryUi.icon(c, R.drawable.ic_close, "閉じる", this::dismiss));
    title.setPadding(0, 0, 0, 0);
    body.addView(title);
    body.addView(label(c, "順位"));
    GridLayout rankGrid = new GridLayout(c);
    rankGrid.setColumnCount(2);
    rankGrid.setBackground(GalleryUi.shape(c, GalleryUi.PANEL, 24));
    for (int i = 0; i < RANKS.length; i++) {
      final int value = i;
      MaterialButton b =
          choice(
              c,
              new String[] {"すべて", "1位", "未決定", "決定済み"}[i],
              RANKS[i],
              () -> {
                this.rank = value;
                update();
              });
      ranks.add(b);
      GridLayout.LayoutParams p =
          new GridLayout.LayoutParams(GridLayout.spec(i / 2), GridLayout.spec(i % 2, 1f));
      p.width = 0;
      p.height = -2;
      p.setMargins(dp(2), dp(2), dp(2), dp(2));
      rankGrid.addView(b, p);
    }
    body.addView(rankGrid, new LinearLayout.LayoutParams(-1, -2));
    body.addView(label(c, "撮影元"));
    GridLayout cameraGrid = new GridLayout(c);
    cameraGrid.setColumnCount(3);
    for (int i = 0; i < CAMERAS.length; i++) {
      final String value = CAMERAS[i];
      MaterialButton b =
          choice(
              c,
              value,
              value,
              () -> {
                this.camera = value;
                update();
              });
      cameras.add(b);
      GridLayout.LayoutParams p =
          new GridLayout.LayoutParams(GridLayout.spec(i / 3), GridLayout.spec(i % 3, 1f));
      p.width = 0;
      p.height = -2;
      p.setMargins(dp(2), dp(2), dp(2), dp(2));
      cameraGrid.addView(b, p);
    }
    body.addView(cameraGrid, new LinearLayout.LayoutParams(-1, -2));
    countFailure = PhotoListUi.text(c, "件数を読み込めませんでした", 14, 20, 0xffffb4a8);
    countFailure.setTag("photo-filter-count-failure");
    countFailure.setPadding(0, dp(12), 0, 0);
    countFailure.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    countFailure.setVisibility(View.GONE);
    body.addView(countFailure, new LinearLayout.LayoutParams(-1, -2));
    LinearLayout footer = new LinearLayout(c);
    footer.setGravity(Gravity.CENTER_VERTICAL);
    footer.setPadding(0, dp(20), 0, 0);
    footer.addView(
        LibraryUi.text(
            c,
            "リセット",
            () -> {
              this.camera = "すべて";
              this.rank = 0;
              update();
            }));
    apply =
        LibraryUi.text(
            c,
            "読み込み中",
            () -> {
              if (result.state() == State.FAILED) {
                this.retry.run();
                update();
              } else if (result.state() == State.READY) {
                if (!result.current().getAsBoolean()) {
                  update();
                  return;
                }
                String chosen = this.camera;
                int chosenRank = this.rank;
                dismiss();
                committed.accept(chosen, chosenRank);
              }
            });
    apply.setTag("photo-filter-apply");
    MaterialUi.filled(apply);
    footer.addView(apply, new LinearLayout.LayoutParams(0, -2, 1));
    body.addView(footer);
    setContentView(scroll);
    MaterialUi.sheet(this);
    getBehavior().setDraggable(true);
    setOnShowListener(dialog -> update());
    setOnDismissListener(dialog -> request++);
  }

  void refreshCount() {
    if (isShowing()) update();
  }

  private void update() {
    for (int i = 0; i < ranks.size(); i++) style(ranks.get(i), i == rank);
    for (int i = 0; i < cameras.size(); i++) style(cameras.get(i), CAMERAS[i].equals(camera));
    int token = ++request;
    showCount(CountResult.pending());
    count.request(
        camera,
        rank,
        value -> {
          if (!isShowing() || token != request) return;
          showCount(value);
        });
  }

  private void showCount(CountResult value) {
    result = value;
    countFailure.setVisibility(value.state() == State.FAILED ? View.VISIBLE : View.GONE);
    apply.setEnabled(value.state() != State.PENDING);
    apply.setText(
        switch (value.state()) {
          case PENDING -> "読み込み中";
          case FAILED -> "件数を再確認";
          case READY -> value.count() + "件を表示";
        });
    apply.setContentDescription(value.state() == State.FAILED ? "件数を確認できませんでした。再試行" : null);
  }

  private void style(MaterialButton b, boolean selected) {
    b.setEnabled(true);
    MaterialUi.choice(b, selected);
    if (!selected) {
      MaterialUi.text(b);
      b.setBackgroundTintList(ColorStateList.valueOf(GalleryUi.PANEL));
    }
    b.setStateDescription(selected ? "選択中" : "未選択");
  }

  private MaterialButton choice(Context c, String label, String spoken, Runnable action) {
    MaterialButton b = LibraryUi.text(c, label, action);
    b.setContentDescription(spoken);
    b.setCornerRadius(dp(20));
    return b;
  }

  private TextView label(Context c, String name) {
    TextView t = PhotoListUi.text(c, name, 14, 20, GalleryUi.TEXT);
    t.setPadding(0, dp(20), 0, dp(12));
    t.setAccessibilityHeading(true);
    return t;
  }

  private int dp(int v) {
    return GalleryUi.dp(getContext(), v);
  }
}
