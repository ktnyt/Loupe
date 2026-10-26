package jp.nano.rawgallery;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

/** Photo-led horizontal lists; data loading and navigation belong to MainActivity. */
final class GalleryCarouselUi {
  private static int dp(Context c,int value){return GalleryUi.dp(c,value);}
  private static TextView text(Context c,String value,int size,int color,int line){
    TextView t=new TextView(c);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);t.setLineHeight(Math.round(t.getTextSize()*line/size));return t;
  }
  static LinearLayout section(Context c,LinearLayout root,String title,boolean recent){
    LinearLayout section=new LinearLayout(c);section.setOrientation(LinearLayout.VERTICAL);
    LinearLayout heading=new LinearLayout(c);heading.setGravity(Gravity.CENTER_VERTICAL);
    heading.setPadding(dp(c,20),dp(c,recent?40:24),dp(c,20),dp(c,12));
    TextView label=text(c,title,17,GalleryUi.TEXT,24);label.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));label.setAccessibilityHeading(true);
    heading.addView(label,new LinearLayout.LayoutParams(0,-2,1));
    TextView date=text(c,"",13,0xffa9a9a4,18);date.setTag("carousel-date");heading.addView(date);section.addView(heading);
    HorizontalScrollView scroll=new HorizontalScrollView(c);scroll.setHorizontalScrollBarEnabled(false);
    LinearLayout cards=new LinearLayout(c);cards.setGravity(Gravity.TOP);cards.setPadding(dp(c,16),0,dp(c,16),0);
    scroll.addView(cards,new HorizontalScrollView.LayoutParams(-2,-2));section.addView(scroll,new LinearLayout.LayoutParams(-1,-2));root.addView(section);
    return cards;
  }
  static ImageView photo(Context c,LinearLayout cards,String name,boolean recent,Runnable open){
    LinearLayout card=new LinearLayout(c);card.setOrientation(LinearLayout.VERTICAL);card.setFocusable(true);card.setClickable(true);card.setContentDescription(name);card.setOnClickListener(v->{GalleryUi.feedback(v);open.run();});
    FrameLayout frame=new FrameLayout(c);ImageView image=new ImageView(c);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    frame.setBackground(GalleryUi.shape(c,GalleryUi.PANEL,recent?14:8));frame.setClipToOutline(true);frame.addView(image,new FrameLayout.LayoutParams(-1,-1));
    card.addView(frame,new LinearLayout.LayoutParams(-1,dp(c,recent?225:78)));
    TextView caption=text(c,name,13,GalleryUi.TEXT,18);caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    if(recent){caption.setPadding(dp(c,12),0,dp(c,12),dp(c,10));caption.setGravity(Gravity.BOTTOM);caption.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x00000000,0xaa000000}));FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-1,dp(c,56),Gravity.BOTTOM);frame.addView(caption,p);}
    else{LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(c,6);card.addView(caption,p);}
    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(c,recent?300:104),-2);p.rightMargin=dp(c,8);cards.addView(card,p);return image;
  }
}
