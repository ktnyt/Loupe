package jp.nano.rawgallery;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.material.button.MaterialButton;

/** Library hierarchy only; committed navigation, filters and selection belong to the activity. */
final class LibraryUi {
  static LinearLayout toolbar(Context c, String name, View... actions) {
    LinearLayout bar = new LinearLayout(c);
    bar.setTag("library-toolbar");
    bar.setGravity(Gravity.CENTER_VERTICAL);
    bar.setPadding(dp(c,16),dp(c,4),dp(c,8),dp(c,4));
    TextView title = PhotoListUi.text(c,name,26,34,GalleryUi.TEXT);
    title.setTypeface(Typeface.create(Typeface.DEFAULT,600,false));
    title.setAccessibilityHeading(true);
    bar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
    for(View action:actions)bar.addView(action,new LinearLayout.LayoutParams(-2,-2));
    return bar;
  }
  static MaterialButton text(Context c,String label,Runnable action) {
    MaterialButton button=(MaterialButton)GalleryUi.button(c,label,action);
    button.setCompoundDrawables(null,null,null,null);
    button.setTextSize(14);button.setPadding(dp(c,12),dp(c,8),dp(c,12),dp(c,8));
    MaterialUi.text(button);return button;
  }
  static MaterialButton icon(Context c,int resource,String label,Runnable action) {
    MaterialButton button=MaterialUi.button(c);
    button.setIconResource(resource);button.setIconSize(dp(c,24));
    button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);button.setIconPadding(0);
    button.setPadding(dp(c,12),0,dp(c,12),0);
    button.setContentDescription(label);button.setTooltipText(label);
    button.setOnClickListener(v->{GalleryUi.feedback(v);try(DiagnosticLog.Scope ignored=DiagnosticLog.span("action.button")){action.run();}});
    return button;
  }
  static LinearLayout metadata(Context c,TextView subtitle,StatusView status) {
    LinearLayout row=new LinearLayout(c);row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(c,20),0,dp(c,16),dp(c,8));
    subtitle.setTextSize(13);row.addView(subtitle,new LinearLayout.LayoutParams(0,-2,1));
    status.collapseWhenIdle();row.addView(status);return row;
  }
  static View actionBar(Context c,Button tag,Button compare,Button share) {
    LinearLayout bar=new LinearLayout(c);bar.setGravity(Gravity.CENTER_VERTICAL);
    bar.setPadding(dp(c,6),dp(c,6),dp(c,6),dp(c,6));
    bar.setBackground(GalleryUi.shape(c,GalleryUi.PANEL,28));
    LinearLayout tools=new LinearLayout(c);tools.setBackground(GalleryUi.shape(c,0xff303744,24));
    for(Button button:new Button[]{tag,compare}) {
      MaterialUi.text(button);button.setTextSize(14);button.setMinHeight(dp(c,52));button.setMinimumHeight(dp(c,52));button.setCompoundDrawables(null,null,null,null);
      tools.addView(button,new LinearLayout.LayoutParams(0,-2,1));
    }
    bar.addView(tools,new LinearLayout.LayoutParams(0,-2,2));
    share.setTextSize(14);share.setMinHeight(dp(c,52));share.setMinimumHeight(dp(c,52));share.setCompoundDrawables(null,null,null,null);
    LinearLayout.LayoutParams primary=new LinearLayout.LayoutParams(0,-2,1.35f);primary.leftMargin=dp(c,8);bar.addView(share,primary);
    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
    p.setMargins(dp(c,12),dp(c,6),dp(c,12),dp(c,10));bar.setLayoutParams(p);return bar;
  }
  private static int dp(Context c,int value){return GalleryUi.dp(c,value);}
}
