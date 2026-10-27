package jp.nano.rawgallery;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;

/** Layout only. Permission checks, stored values and actions remain owned by SyncActivity. */
final class SyncSettingsUi {
  private static int dp(Context c,float n){return GalleryUi.dp(c,n);}
  static TextView text(Context c,String value,int size,int color,float line){
    TextView t=new TextView(c);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);t.setLineHeight(Math.round(t.getTextSize()*line));return t;
  }
  private static LinearLayout column(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);return l;}
  private static LinearLayout card(Context c,LinearLayout cards,String name){
    LinearLayout card=column(c);card.setPadding(dp(c,16),dp(c,16),dp(c,16),dp(c,16));card.setBackground(GalleryUi.shape(c,GalleryUi.PANEL,16));
    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);if(cards.getChildCount()>0)lp.topMargin=dp(c,12);cards.addView(card,lp);
    TextView title=text(c,name,16,GalleryUi.TEXT,22f/16);title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));title.setAccessibilityHeading(true);
    LinearLayout.LayoutParams heading=new LinearLayout.LayoutParams(-1,-2);heading.bottomMargin=dp(c,8);card.addView(title,heading);return card;
  }
  private static void line(Context c,LinearLayout card){View line=new View(c);line.setBackgroundColor(0xff26262a);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(c,1));lp.topMargin=dp(c,12);lp.bottomMargin=dp(c,10);card.addView(line,lp);}
  private static void row(Context c,LinearLayout card,String label,View value,boolean divider){
    if(divider)line(c,card);LinearLayout row=new LinearLayout(c);row.setGravity(Gravity.CENTER_VERTICAL);row.setMinimumHeight(dp(c,32));
    TextView name=text(c,label,13,0xffa9a9a4,20f/13);LinearLayout.LayoutParams labelParams=new LinearLayout.LayoutParams(0,-2,1);labelParams.rightMargin=dp(c,12);row.addView(name,labelParams);
    // Summary is an existing multi-line string, never truncate it to a numeric fixture.
    if(value instanceof TextView&&!(value instanceof Button)){
      TextView t=(TextView)value;t.setPadding(0,0,0,0);t.setTextSize(14);t.setTextColor(GalleryUi.TEXT);t.setIncludeFontPadding(false);t.setLineHeight(Math.round(t.getTextSize()*1.45f));
      t.setGravity(Gravity.END);row.addView(value,new LinearLayout.LayoutParams(0,-2,1));
    }else row.addView(value,new LinearLayout.LayoutParams(value instanceof StatusView?dp(c,value.isClickable()?48:24):-2,-2));
    card.addView(row,new LinearLayout.LayoutParams(-1,-2));
  }
  private static void description(Context c,LinearLayout card,String title,String body,boolean first){
    if(title!=null){TextView label=text(c,title,13,0xffd6d6d2,20f/13);label.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(c,first?0:20);p.bottomMargin=dp(c,2);card.addView(label,p);}
    card.addView(text(c,body,13,0xffa9a9a4,20f/13),new LinearLayout.LayoutParams(-1,-2));
  }
  static void styleButton(Context c,Button b,boolean primary,int size,int min){
    b.setAllCaps(false);b.setTextSize(size);b.setTextColor(primary?0xff111111:GalleryUi.TEXT);b.setIncludeFontPadding(false);b.setLineHeight(Math.round(b.getTextSize()*1.35f));b.setSingleLine(false);b.setEllipsize(null);b.setGravity(Gravity.CENTER);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
    b.setMinHeight(dp(c,min));b.setMinimumHeight(dp(c,min));b.setPadding(dp(c,b instanceof ActionButton&&hasIcon(b)?44:16),dp(c,10),dp(c,16),dp(c,10));
    GradientDrawable shape=GalleryUi.shape(c,primary?GalleryUi.TEXT:Color.TRANSPARENT,min/2);if(!primary)shape.setStroke(dp(c,1),0xff3a3a3f);
    android.graphics.drawable.StateListDrawable states=new android.graphics.drawable.StateListDrawable();GradientDrawable focus=GalleryUi.shape(c,primary?GalleryUi.TEXT:Color.TRANSPARENT,min/2);focus.setStroke(dp(c,2),primary?0xff111111:GalleryUi.TEXT);states.addState(new int[]{android.R.attr.state_focused},focus);states.addState(new int[]{},shape);
    b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(primary?0x22000000:0x33ffffff),states,GalleryUi.shape(c,Color.WHITE,min/2)));
  }
  private static void action(Context c,LinearLayout card,Button b,String label){if(b==null)return;b.setText(label);b.setContentDescription(label);styleButton(c,b,false,14,48);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(c,8);card.addView(b,p);}
  private static void headingControl(Context c,LinearLayout card,Switch control,String description){
    View title=card.getChildAt(0);card.removeView(title);LinearLayout header=new LinearLayout(c);header.setGravity(Gravity.CENTER_VERTICAL);
    header.addView(title,new LinearLayout.LayoutParams(0,-2,1));control.setText("");control.setContentDescription(description);control.setMinimumWidth(dp(c,52));control.setSwitchMinWidth(dp(c,52));control.setSplitTrack(false);control.setPadding(0,0,0,0);
    GradientDrawable track=new GradientDrawable(){
      @Override public boolean getPadding(Rect padding){padding.set(dp(c,6),0,dp(c,6),0);return true;}
    };track.setColor(GalleryUi.TEXT);track.setCornerRadius(dp(c,16));track.setSize(dp(c,52),dp(c,32));control.setTrackDrawable(track);
    android.graphics.drawable.Drawable thumb=new android.graphics.drawable.Drawable(){
      private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
      @Override public int getIntrinsicWidth(){return dp(c,20);}
      @Override public int getIntrinsicHeight(){return dp(c,20);}
      @Override public boolean isStateful(){return true;}
      @Override protected boolean onStateChange(int[] state){invalidateSelf();return true;}
      @Override public void draw(Canvas canvas){paint.setColor(control.isChecked()?0xff111111:0xffa9a9a4);Rect bounds=getBounds();canvas.drawCircle(bounds.exactCenterX(),bounds.exactCenterY(),dp(c,10),paint);}
      @Override public void setAlpha(int alpha){paint.setAlpha(alpha);}
      @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);}
      @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    };control.setThumbDrawable(thumb);control.setThumbTintList(null);
    control.setTrackTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{GalleryUi.TEXT,0xff3a3a3f}));
    LinearLayout.LayoutParams controlParams=new LinearLayout.LayoutParams(dp(c,52),dp(c,48));controlParams.leftMargin=dp(c,12);header.addView(control,controlParams);card.addView(header,0);
  }
  static void verification(Context c,LinearLayout root,StatusView status,Button action){
    LinearLayout card=root.findViewWithTag("verification-card");View title=card.getChildAt(0);card.removeView(title);
    LinearLayout header=new LinearLayout(c);header.setGravity(Gravity.CENTER_VERTICAL);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));header.addView(status,new LinearLayout.LayoutParams(dp(c,24),dp(c,24)));card.addView(header,0);
    action(c,card,action,"確認する");
  }
  private static boolean hasIcon(Button b){String label=b.getText().toString();return label.contains("位置情報")||label.contains("アクセス")||label.equals("状態を更新");}
  static TextView populate(Context c,LinearLayout root,LinearLayout screen,TextView info,StatusView collection,StatusView permission,StatusView progress,TextView progressTime,Switch sync,Switch cleanup,Button allFiles,Button location,Button keep,Button settings,Button other){
    LinearLayout heading=column(c);heading.setMinimumHeight(dp(c,424));heading.setGravity(Gravity.BOTTOM);heading.setPadding(dp(c,20),0,dp(c,20),dp(c,20));
    TextView title=text(c,"クラウドと同期",28,GalleryUi.TEXT,36f/28);title.setTypeface(Typeface.create(Typeface.create("sans-serif",Typeface.NORMAL),600,false));title.setAccessibilityHeading(true);heading.addView(title);TextView last=text(c,"最後の同期：—",14,0xffa9a9a4,20f/14);last.setTag("last-sync");heading.addView(last);root.addView(heading);
    LinearLayout cards=column(c);cards.setPadding(dp(c,16),0,dp(c,16),dp(c,32));root.addView(cards);
    LinearLayout save=card(c,cards,"同期");headingControl(c,save,sync,"自動同期");
    LinearLayout job=new LinearLayout(c);job.setGravity(Gravity.CENTER_VERTICAL);job.addView(progress,new LinearLayout.LayoutParams(dp(c,24),dp(c,32)));progressTime.setTextSize(13);progressTime.setTextColor(0xffa9a9a4);job.addView(progressTime);LinearLayout header=(LinearLayout)save.getChildAt(0);header.addView(job,1);
    save.addView(text(c,"次の同期を予約します。すぐに始まるとは限りません",13,0xffa9a9a4,20f/13));
    row(c,save,"写真情報の読み取り",collection,false);row(c,save,"写真の集計",info,false);
    action(c,save,settings,"同期を予約する");action(c,save,other,"状態を更新");line(c,save);
    description(c,save,"原本の保存","フォルダ選択は不要です。すべてのJPEGと、RAW内の機種情報からGR IV（Monochrome含む）、OM-1 Mark II、E-P7、LeitzphoneのRAWを自動で見つけます。",true);description(c,save,"モバイル通信","モバイル通信でも同期します。実行時刻はAndroidの省電力制御などにより遅れる場合があります。",false);
    description(c,save,"原本の位置情報","撮影時の位置情報も含め、元のファイルを変更せず保存するための許可です。端末の現在位置は取得しません。",false);action(c,save,location,"位置情報の読み取りを許可");
    LinearLayout verification=card(c,cards,"保存した写真の確認");verification.setTag("verification-card");
    verification.addView(text(c,"クラウドに保存した写真を、元どおりダウンロードできるか確認します",13,0xffa9a9a4,20f/13));
    LinearLayout delete=card(c,cards,"端末の写真を自動で削除");headingControl(c,delete,cleanup,"クラウドに保存済みの写真を端末から自動で削除");
    delete.addView(text(c,"クラウドに保存済みの写真を、この端末から削除します。クラウドの写真は残ります。",13,GalleryUi.TEXT,20f/13));
    delete.addView(text(c,"保存内容の確認とプレビューの保存が済んだ写真だけが対象です。",13,0xffa9a9a4,20f/13));row(c,delete,"自動管理の権限",permission,false);
    TextView retention=text(c,"—",13,0xffa9a9a4,18f/13);LinearLayout period=new LinearLayout(c);period.setGravity(Gravity.CENTER_VERTICAL);LinearLayout labels=column(c);labels.addView(text(c,"端末に残す期間",14,GalleryUi.TEXT,20f/14));labels.addView(retention);period.addView(labels,new LinearLayout.LayoutParams(0,-2,1));keep.setText("変更");keep.setContentDescription("端末に残す期間を変更");styleButton(c,keep,false,14,48);period.addView(keep,new LinearLayout.LayoutParams(-2,-2));delete.addView(period);line(c,delete);
    description(c,delete,"削除の条件","端末の写真の自動削除には「すべてのファイルへのアクセス」が必要です。端末から削除するのは対象機種のRAWとすべてのJPEGで、クラウドから同じ内容で戻せることを確認し、プレビューを保存した元のファイルに限ります。",true);
    action(c,delete,allFiles,"写真の自動削除に必要なアクセスを許可");description(c,delete,"日付の条件","撮影日から数えます。JPEGで撮影日がない場合は追加日、作成日の順に使います。日付がすべて不明のJPEGは保存確認後に削除対象になり、撮影日不明のRAWは残します。",false);description(c,delete,"アプリを削除したとき","アプリを削除すると端末のプレビューと管理記録も消えます。クラウドの元のファイルはAmazon Photosに残ります。",false);
    return retention;
  }

  static final class ActionButton extends Button {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    ActionButton(Context c,String label,Runnable action){super(c);setText(label);setMinWidth(0);setMinimumWidth(0);setOnClickListener(v->{GalleryUi.feedback(v);try(DiagnosticLog.Scope ignored=DiagnosticLog.span("action.button")){action.run();}});}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);if(!hasIcon(this))return;android.text.Layout layout=getLayout();if(layout==null)return;
      float left=getCompoundPaddingLeft()+layout.getLineLeft(0)-dp(getContext(),28),top=getHeight()/2f-dp(getContext(),10);int save=canvas.save();canvas.translate(left,top);float d=getResources().getDisplayMetrics().density;canvas.scale(d,d);
      paint.setColor(getCurrentTextColor());paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.7f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);String label=getText().toString();
      if(label.equals("その他")){paint.setStyle(Paint.Style.FILL);for(int x:new int[]{4,10,16})canvas.drawCircle(x,10,1.4f,paint);}
      else if(label.contains("位置情報")){canvas.drawCircle(10,8,2.3f,paint);Path p=new Path();p.moveTo(10,18);p.cubicTo(2,10,2,3,10,2);p.cubicTo(18,3,18,10,10,18);canvas.drawPath(p,paint);}
      else if(label.contains("期間")){canvas.drawRoundRect(3,4,17,18,2,2,paint);canvas.drawLine(3,8,17,8,paint);canvas.drawLine(7,2,7,6,paint);canvas.drawLine(13,2,13,6,paint);}
      else if(label.contains("アクセス"))StatusView.glyph(canvas,paint,StatusView.Symbol.AUTH,0,0,20,getCurrentTextColor(),0);
      else {canvas.drawArc(3,3,17,17,210,145,false,paint);canvas.drawArc(3,3,17,17,30,145,false,paint);canvas.drawLine(17,3,17,8,paint);canvas.drawLine(17,8,12,8,paint);canvas.drawLine(3,17,3,12,paint);canvas.drawLine(3,12,8,12,paint);}
      canvas.restoreToCount(save);
    }
  }
}
