package jp.nano.rawgallery;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.*;
import java.util.*;

/** Synthetic rendering assertions; never opens an external app or reads production history. */
final class ShareHistoryUiChecks {
  static void run(Context context) {
    List<ShareHistoryUi.Item> entries=new ArrayList<>();
    for(int i=0;i<7;i++)entries.add(new ShareHistoryUi.Item(new ShareHistoryStore.Entry("example.app"+i,"App "+i,100-i),new ShareHistoryUi.App(null,false)));
    String spoken=ShareHistoryUi.description(entries);
    check(spoken.contains("7件")&&spoken.contains("ほか5件")&&spoken.contains("使えないアプリ"),"full accessible count");
    check(ShareHistoryUi.description(Collections.emptyList()).isEmpty(),"empty history label");
    for(float font:new float[]{1,1.3f}) {
      Configuration config=new Configuration(context.getResources().getConfiguration());config.fontScale=font;
      ShareHistoryUi.Painter painter=new ShareHistoryUi.Painter(context.createConfigurationContext(config));
      for(int count:new int[]{0,1,2,7})for(boolean cloud:new boolean[]{false,true})for(int width:new int[]{78,90,98}) {
        Bitmap bitmap=Bitmap.createBitmap(width,96,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);
        // Selected 320dp tile: rank occupies x=14..34, cloud keeps bottom-right location.
        painter.draw(canvas,entries.subList(0,count),width-15,81,width-15-36,width-30,14,20,2,cloud,false);
        for(int y=61;y<81;y++)for(int x=14;x<34;x++)check(Color.alpha(bitmap.getPixel(x,y))==0,"badge must not cover rank");
        for(int y=0;y<96;y++)for(int x=width-14;x<width;x++)check(Color.alpha(bitmap.getPixel(x,y))==0,"selected photo right inset");
        if(count==0&&!cloud)check(Color.alpha(bitmap.getPixel(width-20,75))==0,"no empty badge");
        bitmap.recycle();
      }
    }
    for(float font:new float[]{1,1.3f}) {
      Configuration config=new Configuration(context.getResources().getConfiguration());config.fontScale=font;
      ShareHistoryUi.Painter painter=new ShareHistoryUi.Painter(context.createConfigurationContext(config));
      Bitmap bitmap=Bitmap.createBitmap(78,96,Bitmap.Config.ARGB_8888);
      float drawn=painter.draw(new Canvas(bitmap),entries,63,81,12,48,14,20,2,false,false);
      check(drawn>0,"local history survives rank crowding");
      boolean visible=false;
      for(int y=39;y<59;y++)for(int x=15;x<63;x++)visible|=Color.alpha(bitmap.getPixel(x,y))>0;
      check(visible,"local history moves above rank");
      for(int y=61;y<81;y++)for(int x=15;x<51;x++)check(Color.alpha(bitmap.getPixel(x,y))==0,"local fallback preserves rank");
      bitmap.recycle();
    }
    ShareHistoryUi.Overlay overlay=new ShareHistoryUi.Overlay(context,()->new RectF(0,0,200,200),12,4,false);
    check(!overlay.isClickable()&&!overlay.isFocusable(),"decoration preserves photo tap");
    check(overlay.getImportantForAccessibility()==android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO,"decoration not a separate AX target");
  }
  private static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
}
