package jp.nano.rawgallery;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.Supplier;

/** Display-only snapshot: database and package resolution happen off the UI thread. */
final class ShareHistoryUi {
  static final class App {
    final Bitmap icon;
    final boolean available;
    App(Bitmap icon, boolean available) { this.icon=icon; this.available=available; }
  }
  static final class Item {
    final ShareHistoryStore.Entry entry;
    final App app;
    Item(ShareHistoryStore.Entry entry, App app) { this.entry=entry; this.app=app; }
    String spoken() { return entry.label+(app.available?"":"（使えないアプリ）"); }
  }
  static Map<String,List<Item>> load(Context context) {
    Map<String,List<ShareHistoryStore.Entry>> stored;
    try (ShareHistoryStore db=new ShareHistoryStore(context)) { stored=db.snapshot(); }
    Map<String,App> apps=new HashMap<>();
    Map<String,List<Item>> result=new HashMap<>();
    for (Map.Entry<String,List<ShareHistoryStore.Entry>> row:stored.entrySet()) {
      List<Item> items=new ArrayList<>();
      for (ShareHistoryStore.Entry entry:row.getValue()) {
        App app=apps.get(entry.packageName);
        if(app==null){
          Bitmap icon=null; boolean available=false;
          try {
            android.content.pm.ApplicationInfo info=context.getPackageManager().getApplicationInfo(entry.packageName,0);
            available=info.enabled;
            if(available){Drawable source=context.getPackageManager().getApplicationIcon(info);
              int edge=GalleryUi.dp(context,32);icon=Bitmap.createBitmap(edge,edge,Bitmap.Config.ARGB_8888);
              source.setBounds(0,0,edge,edge);source.draw(new Canvas(icon));}
          } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException ignored) { available=false; }
          app=new App(icon,available);apps.put(entry.packageName,app);
        }
        items.add(new Item(entry,app));
      }
      result.put(row.getKey(),Collections.unmodifiableList(items));
    }
    return Collections.unmodifiableMap(result);
  }
  static String description(List<Item> items) {
    if(items.isEmpty())return "";
    String text="、共有したアプリ "+items.size()+"件："+items.get(0).spoken();
    if(items.size()>1)text+="、"+items.get(1).spoken();
    if(items.size()>2)text+=" ほか"+(items.size()-2)+"件";
    return text;
  }
  static boolean added(List<Item> old,List<Item> value){
    for(Item item:value){boolean present=false;for(Item before:old)if(before.entry.packageName.equals(item.entry.packageName)&&before.entry.handedOffAt>=item.entry.handedOffAt){present=true;break;}if(!present)return true;}return false;
  }
  static long animationStart(){return android.animation.ValueAnimator.areAnimatorsEnabled()?android.os.SystemClock.uptimeMillis():0;}
  static int alpha(long started){return started==0?255:Math.min(255,(int)((android.os.SystemClock.uptimeMillis()-started)*255/180));}
  static final class Painter {
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Path clip=new Path();
    private final Drawable cloud;
    private final float fontScale;
    Painter(Context context){cloud=context.getDrawable(R.drawable.ic_selection_cloud);fontScale=context.getResources().getConfiguration().fontScale;}
    private float width(int count,int remaining,float size,float step,float pad,boolean withCloud){
      return pad*2+(count>0?size+step*(count-1):0)+(remaining>0?(count>0?2:0)+p.measureText("+"+remaining)+1:0)+(withCloud?(count>0||remaining>0?3:0)+14:0);
    }
    /** Coordinates are logical dp. Returns actual pill width; no touch or accessibility node. */
    float draw(Canvas c,List<Item> items,float right,float bottom,float available,float fullWidth,
        float size,float height,int maximum,boolean withCloud,boolean mini) {
      if(items.isEmpty()&&!withCloud)return 0;
      p.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));p.setTextSize(12*fontScale);p.setStyle(Paint.Style.FILL);
      float step=size-(size==14?4:size==12?4:6),pad=size==14?3:size==12?2:4;
      int count=Math.min(items.size(),maximum),remaining=mini?0:items.size()-count;
      while(count>1&&width(count,remaining,size,step,pad,withCloud)>available){count--;remaining=mini?0:items.size()-count;}
      if(withCloud&&!items.isEmpty()&&fullWidth>0&&width(count,remaining,size,step,pad,true)>available){
        draw(c,items,right,bottom-height-2,fullWidth,0,size,height,maximum,false,mini);
        p.setColor(0x9e000000);c.drawRoundRect(right-24,bottom-height,right,bottom,height/2,height/2,p);
        int save=c.save();c.translate(right-19,bottom-height+(height-14)/2);cloud.setBounds(0,0,14,14);cloud.draw(c);c.restoreToCount(save);return 24;
      }
      if(remaining>0&&width(count,remaining,size,step,pad,withCloud)>available)remaining=0;
      float width=width(count,remaining,size,step,pad,withCloud);
      if(width>available||height>bottom)return 0;
      float left=right-width,top=bottom-height;
      p.setColor(0x9e000000);c.drawRoundRect(left,top,right,bottom,height/2,height/2,p);
      // Draw the newest icon last, so it remains in front of overlapping older icons.
      for(int i=count-1;i>=0;i--)icon(c,items.get(i),left+pad+i*step,top+(height-size)/2,size);
      float x=left+pad+(count>0?size+(count-1)*step:0);
      if(remaining>0){x+=2;p.setStyle(Paint.Style.FILL);p.setColor(Color.WHITE);p.setTextSize(12*fontScale);Paint.FontMetrics fm=p.getFontMetrics();c.drawText("+"+remaining,x,top+height/2-(fm.ascent+fm.descent)/2,p);x+=p.measureText("+"+remaining)+1;}
      if(withCloud){if(count>0||remaining>0)x+=3;int save=c.save();c.translate(x,top+(height-14)/2);cloud.setBounds(0,0,14,14);cloud.draw(c);c.restoreToCount(save);}
      return width;
    }
    void icon(Canvas c,Item item,float x,float y,float size){
      float radius=size<=14?4:6;int save=c.save();clip.reset();clip.addRoundRect(x,y,x+size,y+size,radius,radius,Path.Direction.CW);c.clipPath(clip);
      p.setStyle(Paint.Style.FILL);p.setColor(0xff3a3a3f);c.drawRect(x,y,x+size,y+size,p);
      if(item.app.icon!=null&&item.app.available)c.drawBitmap(item.app.icon,null,new RectF(x,y,x+size,y+size),p);
      else {p.setColor(GalleryUi.TEXT);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(Math.max(1,size/14));c.drawRoundRect(x+size*.25f,y+size*.25f,x+size*.75f,y+size*.75f,1,1,p);}
      c.restoreToCount(save);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.5f);p.setColor(0xf20c0c0d);c.drawRoundRect(x+.75f,y+.75f,x+size-.75f,y+size-.75f,radius,radius,p);p.setStyle(Paint.Style.FILL);
    }
  }
  static final class Overlay extends View {
    private List<Item> items=Collections.emptyList();
    private final Painter painter;
    private final Supplier<RectF> bounds;
    private final float bottomInset;
    private final int max;
    private final boolean mini;
    private long appeared;
    Overlay(Context c,Supplier<RectF> bounds,float bottomInset,int max,boolean mini){super(c);this.bounds=bounds;this.bottomInset=bottomInset;this.max=max;this.mini=mini;painter=new Painter(c);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);setClickable(false);setFocusable(false);}
    void items(List<Item> value){items(value,false);}
    void items(List<Item> value,boolean animate){if(animate&&added(items,value))appeared=animationStart();items=value;invalidate();}
    @Override protected void onDraw(Canvas canvas){RectF r=bounds.get();if(r==null||items.isEmpty())return;float d=getResources().getDisplayMetrics().density;int opacity=alpha(appeared);int save=canvas.saveLayerAlpha(0,0,getWidth(),getHeight(),opacity);canvas.scale(d,d);float edge=mini?2:12;painter.draw(canvas,items,r.right/d-edge,r.bottom/d-bottomInset,Math.max(0,r.width()/d-edge*2),0,mini?12:20,mini?16:28,max,false,mini);canvas.restoreToCount(save);if(opacity<255)postInvalidateOnAnimation();}
  }
  static View information(Context c,String details,List<Item> items,boolean loaded,boolean readFailed){
    ScrollView scroll=new ScrollView(c);LinearLayout content=new LinearLayout(c);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(GalleryUi.dp(c,24),0,GalleryUi.dp(c,24),GalleryUi.dp(c,16));scroll.addView(content);
    content.addView(text(c,details,13,GalleryUi.MUTED));
    TextView heading=text(c,"共有したアプリ",15,GalleryUi.TEXT);heading.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));heading.setAccessibilityHeading(true);heading.setPadding(0,GalleryUi.dp(c,16),0,0);content.addView(heading);
    content.addView(text(c,"Loupe から渡した記録（新しい順）。投稿されたかは分かりません。",12,GalleryUi.MUTED));
    if(items.isEmpty())content.addView(text(c,readFailed?"記録を読み込めませんでした":loaded?"まだありません":"読み込み中",13,GalleryUi.MUTED));
    SimpleDateFormat dates=new SimpleDateFormat("yyyy/M/d HH:mm",Locale.getDefault());
    for(Item item:items){LinearLayout row=new LinearLayout(c);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,GalleryUi.dp(c,8),0,GalleryUi.dp(c,8));
      View icon=new View(c){final Painter painter=new Painter(c);protected void onDraw(Canvas canvas){float d=getResources().getDisplayMetrics().density;int save=canvas.save();canvas.scale(d,d);painter.icon(canvas,item,0,0,32);canvas.restoreToCount(save);}};
      icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);row.addView(icon,new LinearLayout.LayoutParams(GalleryUi.dp(c,32),GalleryUi.dp(c,32)));
      LinearLayout labels=new LinearLayout(c);labels.setOrientation(LinearLayout.VERTICAL);labels.setPadding(GalleryUi.dp(c,10),0,0,0);labels.addView(text(c,item.entry.label,14,GalleryUi.TEXT));labels.addView(text(c,dates.format(new Date(item.entry.handedOffAt))+(item.app.available?"":"・使えないアプリ"),12,GalleryUi.MUTED));row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));content.addView(row);
    }
    return scroll;
  }
  private static TextView text(Context c,String value,int size,int color){TextView t=new TextView(c);t.setText(value);t.setTextSize(size);t.setTextColor(color);return t;}
}
