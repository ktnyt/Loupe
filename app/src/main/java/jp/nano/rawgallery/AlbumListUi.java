package jp.nano.rawgallery;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.*;

/** Album list presentation, shared with synthetic device captures. */
final class AlbumListUi {
  private static int dp(Context c,int n){return GalleryUi.dp(c,n);}
  private static TextView text(Context c,String value,int size,int color,int line){TextView v=new TextView(c);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setIncludeFontPadding(false);v.setLineHeight(Math.round(v.getTextSize()*line/size));return v;}
  static LinearLayout heading(Context c,TextView count,StatusView status){
    LinearLayout row=new LinearLayout(c);row.setGravity(android.view.Gravity.CENTER_VERTICAL);row.setPadding(dp(c,20),dp(c,32),dp(c,20),dp(c,16));
    TextView title=text(c,"アルバム",24,GalleryUi.TEXT,32);title.setTypeface(Typeface.create(Typeface.DEFAULT,600,false));title.setAccessibilityHeading(true);row.addView(title,new LinearLayout.LayoutParams(0,-2,1));
    count.setTextSize(14);count.setTextColor(0xffa9a9a4);count.setIncludeFontPadding(false);row.addView(count);status.collapseWhenIdle();row.addView(status);return row;
  }
  static void configureGrid(Context c,ScrollingGrid grid){
    grid.setNumColumns(2);grid.setHorizontalSpacing(dp(c,12));grid.setPadding(0,0,0,dp(c,16));grid.setCellInset(dp(c,16));grid.setCellRowGap(dp(c,20));grid.setClipToPadding(false);
  }
  static LinearLayout cell(Context c){
    LinearLayout cell=new LinearLayout(c);cell.setOrientation(LinearLayout.VERTICAL);
    ImageView cover=new DiagnosticImageView(c);cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);cover.setScaleType(ImageView.ScaleType.CENTER_CROP);cover.setBackground(GalleryUi.shape(c,GalleryUi.PANEL,12));cover.setClipToOutline(true);cell.addView(cover,new LinearLayout.LayoutParams(-1,dp(c,160)));
    TextView caption=text(c,"",15,GalleryUi.TEXT,22);caption.setPadding(dp(c,2),dp(c,8),dp(c,2),0);cell.addView(caption);
    TextView count=text(c,"",13,0xffa9a9a4,18);count.setPadding(dp(c,2),dp(c,3),dp(c,2),0);cell.addView(count);return cell;
  }
}
