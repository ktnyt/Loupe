package jp.nano.rawgallery;

import android.content.Context;
import android.graphics.Typeface;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.util.*;
import java.util.function.*;

/** Completed burst: one large winner and every photo in capture order. */
final class RankingResultUi {
  interface Loader { void load(String id, ImageView image, Runnable failed); }
  static TextView text(Context c,String value,int size,int line,int color){TextView t=new TextView(c);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);t.setLineHeight(Math.round(t.getTextSize()*line/size));return t;}
  static LinearLayout heading(Context c,int count,boolean best){
    LinearLayout box=new LinearLayout(c);box.setOrientation(LinearLayout.VERTICAL);box.setGravity(Gravity.BOTTOM);box.setMinimumHeight(dp(c,424));box.setPadding(dp(c,20),0,dp(c,20),dp(c,16));
    TextView title=text(c,"ソート結果",28,36,GalleryUi.TEXT);title.setTypeface(Typeface.create(Typeface.DEFAULT,600,false));title.setAccessibilityHeading(true);box.addView(title);
    TextView status=text(c,count+"枚",14,20,GalleryUi.MUTED);android.graphics.drawable.Drawable done=new android.graphics.drawable.Drawable(){public void draw(android.graphics.Canvas canvas){StatusView.glyph(canvas,new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG),StatusView.Symbol.COMPLETE,getBounds().left,getBounds().top,getBounds().width(),GalleryUi.TEXT,0);}public void setAlpha(int alpha){}public void setColorFilter(android.graphics.ColorFilter filter){}public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}};done.setBounds(0,0,dp(c,20),dp(c,20));status.setCompoundDrawables(done,null,null,null);status.setCompoundDrawablePadding(dp(c,6));status.setContentDescription(count+"枚、"+(best?"1位を決める方式":"すべての順位を決める方式")+"で順位決定済み");LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(c,4);box.addView(status,p);return box;
  }
  static LinearLayout body(Context c,PhotoRanking ranking,Function<String,String> names,Loader loader,BiConsumer<String,View> open,Runnable resort){
    LinearLayout box=new LinearLayout(c);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(c,16),dp(c,8),dp(c,8),dp(c,12));box.setClipChildren(false);box.setClipToPadding(false);
    Map<Integer,Integer> counts=new HashMap<>();for(String id:ranking.ids)counts.merge(ranking.rankOf(id),1,Integer::sum);
    String winner=ranking.best().get(0);String first=counts.get(1)>1?"=1位":"1位";
    LinearLayout hero=new LinearLayout(c);hero.setOrientation(LinearLayout.VERTICAL);action(hero,summary(ranking)+"。1位の写真："+names.apply(winner)+"。拡大",()->open.accept(winner,hero));hero.setTag("result-hero-"+winner);
    FrameLayout frame=new FrameLayout(c);ImageView image=image(c,6);frame.addView(image,new FrameLayout.LayoutParams(-1,-1));
    TextView rank=text(c,"★ "+first,12,16,0xff241800);rank.setBackground(GalleryUi.shape(c,0xfff5b947,12));rank.setGravity(Gravity.CENTER);rank.setPadding(dp(c,6),dp(c,4),dp(c,8),dp(c,4));FrameLayout.LayoutParams badge=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.LEFT);badge.leftMargin=dp(c,6);badge.topMargin=dp(c,6);frame.addView(rank,badge);
    ImageView zoom=new ImageView(c);zoom.setImageDrawable(new AffordanceIcon(0,0xffffffff));zoom.setPadding(dp(c,6),dp(c,6),dp(c,6),dp(c,6));zoom.setBackground(GalleryUi.shape(c,0x8c000000,16));FrameLayout.LayoutParams zp=new FrameLayout.LayoutParams(dp(c,32),dp(c,32),Gravity.RIGHT|Gravity.BOTTOM);zp.rightMargin=dp(c,6);zp.bottomMargin=dp(c,6);frame.addView(zoom,zp);hero.addView(frame,new LinearLayout.LayoutParams(dp(c,210),dp(c,140)));
    TextView name=text(c,first+" · "+names.apply(winner),14,20,GalleryUi.TEXT);LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,-2);np.topMargin=dp(c,6);hero.addView(name,np);box.addView(hero,new LinearLayout.LayoutParams(-1,-2));hideChildren(hero);
    whenVisible(image,()->loader.load(winner,image,()->failed(image,hero)));
    LinearLayout bottom=new LinearLayout(c);bottom.setGravity(Gravity.BOTTOM);bottom.setClipChildren(false);bottom.setClipToPadding(false);LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,-2);bp.topMargin=dp(c,6);box.addView(bottom,bp);
    Frames frames=new Frames(c);bottom.addView(frames,new LinearLayout.LayoutParams(0,-2,1));
    for(int i=0;i<ranking.ids.size();i++){
      String id=ranking.ids.get(i);int n=ranking.rankOf(id);boolean tie=!ranking.bestOnly()&&counts.get(n)>1;
      LinearLayout item=new LinearLayout(c);item.setOrientation(LinearLayout.VERTICAL);item.setPadding(0,dp(c,5),0,dp(c,4));item.setMinimumHeight(dp(c,52));item.setMinimumWidth(dp(c,48));item.setTag("result-"+id);action(item,(i+1)+"枚目、"+n+"位"+(tie?"（同率）":"")+"、"+names.apply(id)+"。拡大",()->open.accept(id,item));
      FrameLayout outlineBox=new FrameLayout(c);outlineBox.setClipChildren(false);ImageView thumb=image(c,3);if(n!=1)thumb.setAlpha(.6f);else{android.graphics.drawable.GradientDrawable outline=GalleryUi.shape(c,0,6);outline.setStroke(dp(c,2),0xfff5b947);outlineBox.setBackground(outline);}FrameLayout.LayoutParams imageParams=new FrameLayout.LayoutParams(dp(c,33),dp(c,22));imageParams.leftMargin=dp(c,3);imageParams.topMargin=dp(c,3);outlineBox.addView(thumb,imageParams);LinearLayout.LayoutParams frameParams=new LinearLayout.LayoutParams(dp(c,39),dp(c,28));frameParams.leftMargin=-dp(c,3);item.setClipChildren(false);item.setClipToPadding(false);item.setPadding(0,dp(c,2),0,dp(c,4));item.addView(outlineBox,frameParams);
      TextView label=text(c,(tie?"=":"")+n+"位",12,14,n==1?0xfff5b947:GalleryUi.MUTED);label.setGravity(Gravity.CENTER);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(c,33),-2);lp.topMargin=0;item.addView(label,lp);hideChildren(item);frames.addView(item,new ViewGroup.LayoutParams(dp(c,48),-2));whenVisible(thumb,()->loader.load(id,thumb,()->failed(thumb,item)));
    }
    Button reset=GalleryUi.button(c,"再ソート",resort);reset.setTextSize(14);reset.setMinWidth(dp(c,86));reset.setMinimumWidth(dp(c,86));reset.setPadding(dp(c,14),dp(c,4),dp(c,14),dp(c,4));reset.setBackground(GalleryUi.shape(c,0x00000000,24));android.graphics.drawable.GradientDrawable outline=GalleryUi.shape(c,0,24);outline.setStroke(dp(c,1),0xff3a3a3f);reset.setBackground(outline);LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-2,-2);rp.leftMargin=dp(c,8);bottom.addView(reset,rp);return box;
  }
  static void whenVisible(ImageView image,Runnable load){
    ViewTreeObserver.OnPreDrawListener listener=new ViewTreeObserver.OnPreDrawListener(){
      public boolean onPreDraw(){android.graphics.Rect visible=new android.graphics.Rect();if(image.isShown()&&image.getGlobalVisibleRect(visible)&&visible.width()>0&&visible.height()>0){image.getViewTreeObserver().removeOnPreDrawListener(this);load.run();}return true;}
    };
    image.getViewTreeObserver().addOnPreDrawListener(listener);
    image.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View v){}public void onViewDetachedFromWindow(View v){if(image.getViewTreeObserver().isAlive())image.getViewTreeObserver().removeOnPreDrawListener(listener);image.removeOnAttachStateChangeListener(this);}});
  }
  static ImageView image(Context c,int radius){ImageView v=new ImageView(c);v.setScaleType(ImageView.ScaleType.CENTER_CROP);v.setBackground(GalleryUi.shape(c,0xff26262a,radius));v.setClipToOutline(true);return v;}
  static void failed(ImageView image,View target){target.setContentDescription(target.getContentDescription().toString().replace("。拡大","。プレビューを読み込めませんでした。拡大"));image.setScaleType(ImageView.ScaleType.CENTER);image.setImageDrawable(new AffordanceIcon(1,GalleryUi.MUTED,dp(image.getContext(),24)));}
  static String summary(PhotoRanking ranking){
    int count=ranking.ids.size();
    if(ranking.bestOnly())return count+"枚の順位を決めました。1位は"+(ranking.ids.indexOf(ranking.best().get(0))+1)+"枚目"+(count>1?"、ほかの"+(count-1)+"枚は2位":"");
    TreeMap<Integer,List<String>> ranks=new TreeMap<>();
    for(int i=0;i<count;i++)ranks.computeIfAbsent(ranking.rankOf(ranking.ids.get(i)),k->new ArrayList<>()).add((i+1)+"枚目");
    ArrayList<String> parts=new ArrayList<>();
    for(Map.Entry<Integer,List<String>> rank:ranks.entrySet())parts.add(rank.getKey()+"位"+(rank.getValue().size()>1?"（同率）":"")+"は"+String.join("と",rank.getValue()));
    return count+"枚の順位："+String.join("、",parts);
  }
  static void action(View view,String name,Runnable action){view.setContentDescription(name);view.setFocusable(true);view.setClickable(true);view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);view.setOnClickListener(v->{GalleryUi.feedback(v);action.run();});view.setAccessibilityDelegate(new View.AccessibilityDelegate(){public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(Button.class.getName());info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,"拡大"));}});}
  static void hideChildren(ViewGroup parent){for(int i=0;i<parent.getChildCount();i++)parent.getChildAt(i).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);}
  static int dp(Context c,float n){return GalleryUi.dp(c,n);}
  static final class Frames extends ViewGroup{
    Frames(Context c){super(c);setClipChildren(false);setClipToPadding(false);}
    protected void onMeasure(int w,int h){int width=MeasureSpec.getSize(w),x=0,y=0,line=0;for(int i=0;i<getChildCount();i++){View v=getChildAt(i);v.measure(MeasureSpec.makeMeasureSpec(dp(getContext(),48),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));if(x>0&&x+v.getMeasuredWidth()>width){x=0;y+=line;line=0;}x+=v.getMeasuredWidth();line=Math.max(line,v.getMeasuredHeight());}setMeasuredDimension(width,y+line);}
    protected void onLayout(boolean changed,int l,int t,int r,int b){int x=0,y=0,line=0;for(int i=0;i<getChildCount();i++){View v=getChildAt(i);if(x>0&&x+v.getMeasuredWidth()>getWidth()){x=0;y+=line;line=0;}v.layout(x,y,x+v.getMeasuredWidth(),y+v.getMeasuredHeight());x+=v.getMeasuredWidth();line=Math.max(line,v.getMeasuredHeight());}}
  }
}
