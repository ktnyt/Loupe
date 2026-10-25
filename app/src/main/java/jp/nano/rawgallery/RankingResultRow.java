package jp.nano.rawgallery;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import android.text.*;
import android.text.style.StyleSpan;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;

/** One result is one open action; thumbnail and symbol never become separate targets. */
final class RankingResultRow extends LinearLayout {
  final ImageView thumbnail;
  RankingResultRow(Context c, String name) {
    super(c);setOrientation(HORIZONTAL);setGravity(Gravity.CENTER_VERTICAL);
    setPadding(dp(12),dp(6),dp(16),dp(6));setMinimumHeight(dp(84));
    setBackground(new RippleDrawable(ColorStateList.valueOf(0x33c7d8e4),
        GalleryUi.shape(c,GalleryUi.PANEL,12),GalleryUi.shape(c,0xffffffff,12)));
    setContentDescription(name);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    setAccessibilityDelegate(new View.AccessibilityDelegate(){
      public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info){
        super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(Button.class.getName());info.setClickable(true);
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,"拡大"));
      }
      public boolean performAccessibilityAction(View host,int action,android.os.Bundle args){
        if(action==AccessibilityNodeInfo.ACTION_CLICK && host.isEnabled() && host.getParent() instanceof ListView){
          ListView list=(ListView)host.getParent();int position=list.getPositionForView(host);
          if(list.isEnabled() && position!=AdapterView.INVALID_POSITION && list.getAdapter().isEnabled(position))
            return list.performItemClick(host,position,list.getItemIdAtPosition(position));
          return false;
        }
        return super.performAccessibilityAction(host,action,args);
      }
    });
    thumbnail=new ImageView(c);thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
    thumbnail.setBackground(GalleryUi.shape(c,0xff26262a,8));thumbnail.setClipToOutline(true);
    thumbnail.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);addView(thumbnail,new LayoutParams(dp(88),dp(72)));
    TextView text=new TextView(c);SpannableString styled=new SpannableString(name);int end=name.indexOf('位');
    if(end>=0)styled.setSpan(new StyleSpan(Typeface.BOLD),0,end+1,0);
    text.setText(styled);text.setTextSize(14);text.setTextColor(GalleryUi.TEXT);text.setIncludeFontPadding(false);
    text.setLineSpacing(0,1);text.setLineHeight(Math.round(20*c.getResources().getDisplayMetrics().scaledDensity));
    text.setPadding(dp(16),0,0,0);text.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    addView(text,new LayoutParams(0,-2,1));
    ImageView icon=new ImageView(c);icon.setImageDrawable(new AffordanceIcon(0,0xffa0a0a4));
    icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);LayoutParams lp=new LayoutParams(dp(24),dp(24));lp.leftMargin=dp(12);addView(icon,lp);
  }
  void previewFailed(){thumbnail.setScaleType(ImageView.ScaleType.CENTER);thumbnail.setImageDrawable(new AffordanceIcon(1,0xff8a8a85,dp(24)));}
  private int dp(float value){return GalleryUi.dp(getContext(),value);}
}
