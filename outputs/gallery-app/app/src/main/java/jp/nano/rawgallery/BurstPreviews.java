package jp.nano.rawgallery;

import android.content.Context;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.function.*;

/** One visible image first, then one strip image at a time. State belongs to this screen. */
final class BurstPreviews {
  interface Loader { void load(ImageView view, PhotoRepository.Photo photo, int edge, Consumer<Boolean> done); }
  private final ImageView hero;
  private final TextView heroState, position, progress;
  private final ProgressBar heroSpinner;
  private final Button retry;
  private final List<PhotoRepository.Photo> photos;
  private final List<ImageView> images = new ArrayList<>();
  private final List<TextView> labels = new ArrayList<>();
  private final List<ProgressBar> spinners = new ArrayList<>();
  private final int[] states; // 0 waiting, 1 loading, 2 ready, 3 failed
  private final Loader loader;
  private final BooleanSupplier active;
  private int selected = -1, version;
  private boolean heroReady, stripBusy;
  private boolean heroFailed;
  private int dp(Context c, int value) { return Math.round(value * c.getResources().getDisplayMetrics().density); }
  BurstPreviews(Context c, ImageView hero, FrameLayout stage, LinearLayout strip,
      TextView progress, Button retry, List<PhotoRepository.Photo> photos, Loader loader,
      BooleanSupplier active, IntConsumer choose) {
    this.hero = hero; this.progress = progress; this.retry = retry; this.photos = photos;
    this.loader = loader; this.active = active; states = new int[photos.size()];
    heroState = new TextView(c); heroState.setTextColor(GalleryUi.MUTED); heroState.setGravity(Gravity.CENTER);
    stage.addView(heroState, new FrameLayout.LayoutParams(-1, -1));
    heroSpinner = new ProgressBar(c);
    FrameLayout.LayoutParams spinner = new FrameLayout.LayoutParams(dp(c, 32), dp(c, 32), Gravity.CENTER);
    spinner.bottomMargin = dp(c, 72); stage.addView(heroSpinner, spinner);
    position = new TextView(c); position.setTextColor(Color.WHITE); position.setPadding(dp(c, 8),dp(c, 4),dp(c, 8),dp(c, 4));
    position.setBackgroundColor(0xcc19191b);
    FrameLayout.LayoutParams badge = new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM | Gravity.END);
    badge.setMargins(dp(c,16),0,dp(c,16),dp(c,16)); stage.addView(position,badge);
    for (int i=0; i<photos.size(); i++) {
      final int n=i;
      FrameLayout cell = new FrameLayout(c);
      ImageView image = new ImageView(c); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
      image.setBackground(GalleryUi.shape(c, GalleryUi.PANEL,6));
      cell.addView(image,new FrameLayout.LayoutParams(-1,-1)); images.add(image);
      TextView text = new TextView(c); text.setGravity(Gravity.CENTER); text.setTextColor(Color.WHITE);
      cell.addView(text,new FrameLayout.LayoutParams(-1,-1)); labels.add(text);
      ProgressBar spin = new ProgressBar(c);
      cell.addView(spin,new FrameLayout.LayoutParams(dp(c,24),dp(c,24),Gravity.CENTER)); spinners.add(spin);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c,64),dp(c,56)); lp.setMargins(dp(c,3),0,dp(c,3),0);
      strip.addView(cell,lp); cell.setOnClickListener(v -> choose.accept(n));
      cell.setFocusable(true);
    }
    retry.setOnClickListener(v -> {
      for (int i=0;i<states.length;i++) if (states[i]==3) states[i]=0;
      if (heroFailed) { selected=-1; select(currentPosition()); } else pump();
      render();
    });
    render();
  }
  private int currentPosition() { return Integer.parseInt(position.getTag().toString()); }
  void select(int index) {
    if (!active.getAsBoolean() || selected==index) return;
    selected=index; position.setTag(index); heroReady=false; heroFailed=false;
    int request=++version;
    position.setText(photos.size()+"枚中"+(index+1)+"枚目");
    heroState.setText("読み込み中"); heroState.setVisibility(View.VISIBLE); heroSpinner.setVisibility(View.VISIBLE);
    render();
    loader.load(hero,photos.get(index),1600,success -> {
      if (!active.getAsBoolean() || version!=request) return;
      heroFailed=!success;
      heroSpinner.setVisibility(View.GONE);
      heroState.setText(success ? "" : "この写真を読み込めませんでした");
      heroState.setVisibility(success ? View.GONE : View.VISIBLE);
      render();
      if (success) afterDraw(hero, () -> {
        if (active.getAsBoolean() && version==request) { heroReady=true; pump(); }
      });
      else { heroReady=true; pump(); }
    });
  }
  private static void afterDraw(View view, Runnable action) {
    ViewTreeObserver.OnDrawListener listener = new ViewTreeObserver.OnDrawListener() {
      boolean posted;
      public void onDraw() {
        if (posted) return; posted=true;
        view.post(() -> {
          if (view.getViewTreeObserver().isAlive()) view.getViewTreeObserver().removeOnDrawListener(this);
          action.run();
        });
      }
    };
    view.getViewTreeObserver().addOnDrawListener(listener); view.invalidate();
  }
  private void pump() {
    if (!active.getAsBoolean() || !heroReady || stripBusy) return;
    int next=-1;
    if (selected>=0 && states[selected]==0) next=selected;
    else for (int i=0;i<states.length;i++) if (states[i]==0) { next=i; break; }
    if (next<0) { render(); return; }
    final int index=next; states[index]=1; stripBusy=true; render();
    loader.load(images.get(index),photos.get(index),200,success -> {
      if (!active.getAsBoolean()) return;
      states[index]=success?2:3; stripBusy=false; render();
      // Avoid recursive synchronous memory hits and allow a pending user selection to run.
      hero.post(this::pump);
    });
  }
  private void render() {
    int ready=0,failed=0;
    for (int i=0;i<states.length;i++) {
      int state=states[i]; if(state==2) ready++; if(state==3) failed++;
      labels.get(i).setText(state==0?Integer.toString(i+1):state==3?"!":"");
      spinners.get(i).setVisibility(state==1?View.VISIBLE:View.GONE);
      String status=state==0?"待機中":state==1?"読み込み中":state==2?"表示済み":"読み込めませんでした";
      View cell=(View)images.get(i).getParent();
      cell.setContentDescription((i+1)+"枚目、"+status+(i==selected?"、表示中":""));
      android.graphics.drawable.GradientDrawable border=GalleryUi.shape(cell.getContext(),Color.TRANSPARENT,6);
      border.setStroke(dp(cell.getContext(),2),i==selected?GalleryUi.ACCENT:state==3?0xffff8a7a:Color.TRANSPARENT);
      ((FrameLayout)cell).setForeground(border);
    }
    progress.setText(ready==states.length?"":("読み込み済み "+ready+" / "+states.length+"枚")+(failed>0?" · "+failed+"枚失敗":""));
    progress.setVisibility(ready==states.length?View.GONE:View.VISIBLE);
    retry.setVisibility(failed>0||heroFailed?View.VISIBLE:View.GONE);
  }
}
