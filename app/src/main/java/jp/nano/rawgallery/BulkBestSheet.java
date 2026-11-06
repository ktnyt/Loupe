package jp.nano.rawgallery;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Confirm and retry one captured selection. */
final class BulkBestSheet extends Dialog {
  private static final int PANEL = 0xff1c1c1f;
  private static final int OUTLINE = 0xff6b6b70;
  private static final int REPLACE = 0xffff8a7a;
  private static final Typeface SEMIBOLD = Typeface.create(Typeface.DEFAULT, 600, false);
  private final Button confirm, cancel;
  private final TextView error;
  private final ProgressBar progress;
  private final Consumer<Consumer<Boolean>> save;
  private final String action;
  private final boolean complete;
  private boolean busy;

  BulkBestSheet(Activity owner, boolean complete, String action, String summary, int hidden,
      int outside, List<Integer> ordinals, BiConsumer<Integer, ImageView> thumbnail,
      Consumer<Consumer<Boolean>> save) {
    super(owner);
    setOwnerActivity(owner);
    requestWindowFeature(Window.FEATURE_NO_TITLE);
    this.complete = complete;
    this.action = action;
    this.save = save;
    Context c = getContext();

    LinearLayout content = RankingUi.column(c);
    content.setPadding(dp(16), dp(12), dp(16), dp(20));
    View handle = new View(c);
    handle.setBackground(GalleryUi.shape(c, 0xff4a4a50, 2));
    LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(32), dp(4));
    handleParams.gravity = Gravity.CENTER;
    handleParams.bottomMargin = dp(12);
    content.addView(handle, handleParams);

    TextView title = text(action.substring(0, action.length() - "する".length()) + "しますか？", 20, GalleryUi.TEXT);
    title.setTypeface(SEMIBOLD);
    title.setAccessibilityHeading(true);
    title.setLineHeight(sp(28));
    title.setPadding(dp(8), 0, dp(8), dp(4));
    content.addView(title);
    int width = owner.getWindow().getDecorView().getWidth();
    if (width == 0) width = c.getResources().getDisplayMetrics().widthPixels;
    addThumbnails(content, ordinals, thumbnail, width);

    TextView body = text(summary, 14, GalleryUi.TEXT);
    body.setLineHeight(sp(21));
    inset(body);
    RankingUi.add(content, body, 8);
    if (hidden > 0) addNote(content, "絞り込みで見えない" + hidden + "枚を含みます", 8);
    if (outside > 0) addNote(content, "ほかの連写の" + outside + "枚は含みません", hidden > 0 ? 4 : 8);
    error = text("保存できませんでした。順位と選択は前のままです", 13, 0xffffb4a8);
    inset(error);
    error.setCompoundDrawablesRelative(
        StatusView.glyphDrawable(StatusView.Symbol.FAILED, dp(16), REPLACE), null, null, null);
    error.setCompoundDrawablePadding(dp(6));
    error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
    error.setVisibility(View.GONE);
    RankingUi.add(content, error, 8);

    confirm = makeAction(action, 56, 15, false, complete ? OUTLINE : REPLACE, this::start);
    confirm.setTextColor(complete ? GalleryUi.TEXT : REPLACE);
    setConfirmLabel(false);
    FrameLayout confirmFrame = new FrameLayout(c);
    confirmFrame.addView(confirm, new FrameLayout.LayoutParams(-1, -2));
    progress = new ProgressBar(c);
    FrameLayout.LayoutParams progressParams =
        new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.START | Gravity.CENTER_VERTICAL);
    progressParams.leftMargin = dp(16);
    progress.setVisibility(View.GONE);
    progress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    confirmFrame.addView(progress, progressParams);
    RankingUi.add(content, confirmFrame, 16);
    cancel = makeAction("キャンセル", 48, 14, true, 0, this::dismiss);
    RankingUi.add(content, cancel, 8);

    ScrollView scroll = new ScrollView(c) {
      @Override protected void onMeasure(int widthSpec, int heightSpec) {
        Rect frame = new Rect();
        owner.getWindow().getDecorView().getWindowVisibleDisplayFrame(frame);
        int height = frame.height();
        if (height <= 0) height = getResources().getDisplayMetrics().heightPixels;
        int cap = Math.round(height * .85f);
        if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED)
          cap = Math.min(cap, MeasureSpec.getSize(heightSpec));
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
      }
    };
    scroll.addView(content);
    setContentView(scroll);
    setCanceledOnTouchOutside(true);
    Window window = getWindow();
    GradientDrawable bg = GalleryUi.shape(c, PANEL, 28);
    float radius = dp(28);
    bg.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
    window.setBackgroundDrawable(bg);
    window.setGravity(Gravity.BOTTOM);
    window.setWindowAnimations(0);
    window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
    DiagnosticWindow.install(window);
  }

  private void addThumbnails(LinearLayout content, List<Integer> ordinals,
      BiConsumer<Integer, ImageView> thumbnail, int sheetWidth) {
    Context c = getContext();
    int edge = dp(40), gap = dp(6);
    int columns = Math.max(1, (sheetWidth - 2 * dp(24) + gap) / (edge + gap));
    int count = Math.min(6, ordinals.size());
    LinearLayout row = null;
    for (int i = 0; i < count + (ordinals.size() > count ? 1 : 0); i++) {
      if (i % columns == 0) {
        row = new LinearLayout(c);
        inset(row);
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        RankingUi.add(content, row, i == 0 ? 8 : 6);
      }
      FrameLayout tile = new FrameLayout(c);
      tile.setBackground(GalleryUi.shape(c, 0xff26262a, 6));
      tile.setClipToOutline(true);
      LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(edge, edge);
      if (i % columns != 0) tileParams.leftMargin = gap;
      row.addView(tile, tileParams);
      if (i == count) {
        TextView more = text("+" + (ordinals.size() - count), 13, GalleryUi.TEXT);
        more.setGravity(Gravity.CENTER);
        tile.addView(more, new FrameLayout.LayoutParams(-1, -1));
      } else {
        int ordinal = ordinals.get(i);
        ImageView image = new ImageView(c);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        tile.addView(image, new FrameLayout.LayoutParams(-1, -1));
        TextView number = text(Integer.toString(ordinal), 12, Color.WHITE);
        number.setPadding(dp(4), 0, dp(4), 0);
        number.setBackground(GalleryUi.shape(c, 0x9e000000, 6));
        FrameLayout.LayoutParams numberParams =
            new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        numberParams.rightMargin = dp(2);
        numberParams.bottomMargin = dp(2);
        tile.addView(number, numberParams);
        thumbnail.accept(ordinal, image);
      }
    }
  }

  private void addNote(LinearLayout content, String value, int gap) {
    TextView note = text(value, 13, 0xffc9c9c4);
    inset(note);
    RankingUi.add(content, note, gap);
  }

  private int dp(int n) { return GalleryUi.dp(getContext(), n); }

  private int sp(int n) {
    return Math.round(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,
        n, getContext().getResources().getDisplayMetrics()));
  }

  private void inset(View v) { v.setPadding(dp(8), 0, dp(8), 0); }

  private TextView text(String label, int size, int color) {
    TextView view = new TextView(getContext());
    view.setText(label);
    view.setTextSize(size);
    view.setTextColor(color);
    view.setIncludeFontPadding(false);
    return view;
  }

  private Button makeAction(String label, int height, int size, boolean primary, int border,
      Runnable click) {
    Button button = RankingUi.action(GalleryUi.button(getContext(), label, click),
        primary, height, size, null);
    button.setTypeface(SEMIBOLD);
    button.setSingleLine(false);
    button.setEllipsize(null);
    button.setBackground(background(getContext(), primary ? GalleryUi.TEXT : Color.TRANSPARENT,
        primary ? 24 : 26, border));
    return button;
  }

  private void setConfirmLabel(boolean retry) {
    String label = retry ? "再試行\n" + action : action;
    if (complete) RankingUi.inline(confirm, starPlus(GalleryUi.TEXT), label, 20);
    else confirm.setText(label);
    if (retry) {
      SpannableString styled = new SpannableString(confirm.getText());
      int detailStart = styled.length() - action.length();
      int detailEnd = styled.length();
      int detailColor = (complete ? GalleryUi.TEXT : REPLACE) & 0x00ffffff | 0xd9000000;
      styled.setSpan(new AbsoluteSizeSpan(sp(12)), detailStart, detailEnd,
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      styled.setSpan(new TypefaceSpan(Typeface.DEFAULT), detailStart, detailEnd,
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      styled.setSpan(new ForegroundColorSpan(detailColor), detailStart, detailEnd,
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      confirm.setText(styled);
    }
    confirm.setContentDescription(retry ? action + "を再試行" : action);
  }

  private void start() {
    if (busy) return;
    busy = true;
    setCancelable(false);
    setCanceledOnTouchOutside(false);
    error.setVisibility(View.GONE);
    confirm.setEnabled(false);
    confirm.setText("保存しています…");
    confirm.setContentDescription(action + "、保存しています");
    progress.setVisibility(View.VISIBLE);
    cancel.setEnabled(false);
    cancel.setAlpha(.45f);
    save.accept(success -> {
      if (!isShowing()) return;
      busy = false;
      if (success) {
        dismiss();
        return;
      }
      setCancelable(true);
      setCanceledOnTouchOutside(true);
      progress.setVisibility(View.GONE);
      confirm.setEnabled(true);
      cancel.setEnabled(true);
      cancel.setAlpha(1f);
      setConfirmLabel(true);
      error.setVisibility(View.VISIBLE);
    });
  }

  static GradientDrawable background(Context context, int fill, int radius, int border) {
    GradientDrawable shape = GalleryUi.shape(context, fill, radius);
    if (border != 0) shape.setStroke(GalleryUi.dp(context, 1), border);
    return shape;
  }

  static Drawable starPlus(int color) {
    return new Drawable() {
      private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
      private final android.graphics.Path star = new android.graphics.Path();
      {
        star.moveTo(8.5f, 2f);
        star.lineTo(10.7f, 6.4f);
        star.lineTo(15.5f, 7.1f);
        star.lineTo(12f, 10.5f);
        star.lineTo(12.8f, 15.3f);
        star.lineTo(8.5f, 13f);
        star.lineTo(4.2f, 15.3f);
        star.lineTo(5f, 10.5f);
        star.lineTo(1.5f, 7.1f);
        star.lineTo(6.3f, 6.4f);
        star.close();
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.4f);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
      }
      @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        int save = canvas.save();
        canvas.translate(bounds.left, bounds.top);
        canvas.scale(bounds.width() / 20f, bounds.height() / 20f);
        canvas.drawPath(star, paint);
        canvas.drawLine(14, 14, 19, 14, paint);
        canvas.drawLine(16.5f, 11.5f, 16.5f, 16.5f, paint);
        canvas.restoreToCount(save);
      }
      @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
      @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
      @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    };
  }

  /** A dimmed button still announces why it is unavailable when tapped. */
  static final class Entry extends Button {
    private String reason;

    Entry(Context c, Runnable open) {
      super(c);
      RankingUi.action(this, false, 48, 14, null);
      setAllCaps(false);
      setSingleLine(false);
      setEllipsize(null);
      setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
      setPadding(GalleryUi.dp(c, 16), GalleryUi.dp(c, 4), GalleryUi.dp(c, 16), GalleryUi.dp(c, 4));
      setOnClickListener(v -> {
        GalleryUi.feedback(v);
        open.run();
      });
    }

    void update(String label, String description, String disabledReason) {
      reason = disabledReason;
      setEnabled(reason == null);
      int color = reason == null ? GalleryUi.TEXT : 0xff626b72;
      setTextColor(color);
      setBackground(background(getContext(), Color.TRANSPARENT, 24,
          reason == null ? OUTLINE : 0xff2e2e32));
      RankingUi.inline(this, starPlus(color), label, 20);
      setContentDescription(description);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
      if (reason == null) return super.onTouchEvent(event);
      if (event.getActionMasked() == MotionEvent.ACTION_UP && pointInView(event)) {
        announceForAccessibility(reason);
      }
      return true;
    }

    private boolean pointInView(MotionEvent event) {
      return event.getX() >= 0 && event.getX() < getWidth()
          && event.getY() >= 0 && event.getY() < getHeight();
    }
  }
}
