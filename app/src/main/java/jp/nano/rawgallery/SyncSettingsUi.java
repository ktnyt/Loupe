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
    TextView title=text(c,name,13,0xffa0a0a4,1.4f);title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));title.setAccessibilityHeading(true);
    LinearLayout.LayoutParams heading=new LinearLayout.LayoutParams(-1,-2);heading.bottomMargin=dp(c,8);card.addView(title,heading);return card;
  }
  private static void line(Context c,LinearLayout card){View line=new View(c);line.setBackgroundColor(0xff26262a);card.addView(line,new LinearLayout.LayoutParams(-1,dp(c,1)));}
  private static void row(Context c,LinearLayout card,String label,View value,boolean divider){
    if(divider)line(c,card);LinearLayout row=new LinearLayout(c);row.setGravity(Gravity.CENTER_VERTICAL);row.setMinimumHeight(dp(c,48));
    TextView name=text(c,label,16,GalleryUi.TEXT,1.45f);LinearLayout.LayoutParams labelParams=new LinearLayout.LayoutParams(0,-2,1);labelParams.rightMargin=dp(c,12);row.addView(name,labelParams);
    // Summary is an existing multi-line string, never truncate it to a numeric fixture.
    if(value instanceof TextView&&!(value instanceof Button)){
      TextView t=(TextView)value;t.setPadding(0,0,0,0);t.setTextSize(15);t.setTextColor(GalleryUi.TEXT);t.setIncludeFontPadding(false);t.setLineHeight(Math.round(t.getTextSize()*1.45f));
      t.setGravity(Gravity.END);row.addView(value,new LinearLayout.LayoutParams(0,-2,1));
    }else row.addView(value,new LinearLayout.LayoutParams(-2,-2));
    card.addView(row,new LinearLayout.LayoutParams(-1,-2));
  }
  private static void description(Context c,LinearLayout card,String title,String body,boolean first){
    if(title!=null){TextView label=text(c,title,14,GalleryUi.TEXT,1.45f);label.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(c,first?0:12);p.bottomMargin=dp(c,2);card.addView(label,p);}
    card.addView(text(c,body,14,0xffc9c9c4,1.6f),new LinearLayout.LayoutParams(-1,-2));
  }
  static void styleButton(Context c,Button b,boolean primary,int size,int min){
    b.setAllCaps(false);b.setTextSize(size);b.setTextColor(primary?0xff111111:GalleryUi.TEXT);b.setIncludeFontPadding(false);b.setLineHeight(Math.round(b.getTextSize()*1.35f));b.setSingleLine(false);b.setEllipsize(null);b.setGravity(Gravity.CENTER);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
    b.setMinHeight(dp(c,min));b.setMinimumHeight(dp(c,min));b.setPadding(dp(c,b instanceof ActionButton?44:16),dp(c,10),dp(c,16),dp(c,10));
    GradientDrawable shape=GalleryUi.shape(c,primary?GalleryUi.TEXT:0xff26262a,min/2);if(!primary)shape.setStroke(dp(c,1),0xff3a3a3f);
    android.graphics.drawable.StateListDrawable states=new android.graphics.drawable.StateListDrawable();GradientDrawable focus=GalleryUi.shape(c,primary?GalleryUi.TEXT:0xff26262a,min/2);focus.setStroke(dp(c,2),primary?0xff111111:GalleryUi.TEXT);states.addState(new int[]{android.R.attr.state_focused},focus);states.addState(new int[]{},shape);
    b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(primary?0x22000000:0x33ffffff),states,GalleryUi.shape(c,Color.WHITE,min/2)));
  }
  private static void action(Context c,LinearLayout card,Button b,String label){if(b==null)return;b.setText(label);b.setContentDescription(label);styleButton(c,b,false,14,48);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);p.topMargin=dp(c,12);card.addView(b,p);}
  static TextView populate(Context c,LinearLayout root,LinearLayout screen,TextView info,StatusView collection,StatusView permission,StatusView progress,Switch sync,Switch cleanup,Button allFiles,Button location,Button keep,Button settings,Button other){
    TextView title=TagListUi.title(c);title.setText("同期と端末容量");root.addView(title);
    LinearLayout cards=column(c);cards.setPadding(dp(c,16),dp(c,4),dp(c,16),dp(c,16));root.addView(cards);
    LinearLayout config=card(c,cards,"設定");sync.setText("自動同期");cleanup.setText("端末から自動で削除");config.addView(sync,new LinearLayout.LayoutParams(-1,-2));line(c,config);config.addView(cleanup,new LinearLayout.LayoutParams(-1,-2));
    TextView hint=text(c,"変更は［同期・端末からの削除］から",13,0xffa0a0a4,1.5f);hint.setPadding(0,dp(c,8),0,0);config.addView(hint);
    LinearLayout status=card(c,cards,"状態");row(c,status,"写真情報の読み取り",collection,false);row(c,status,"写真の集計",info,true);row(c,status,"自動管理の権限",permission,true);row(c,status,"バックグラウンド同期",progress,true);
    LinearLayout save=card(c,cards,"保存と通信");description(c,save,"原本の保存","フォルダ選択は不要です。すべてのJPEGと、RAW内の機種情報からGR IV（Monochrome含む）、OM-1 Mark II、E-P7、LeitzphoneのRAWを自動で見つけます。",true);description(c,save,"モバイル通信","モバイル通信でも同期します。実行時刻はAndroidの省電力制御などにより遅れる場合があります。",false);
    LinearLayout delete=card(c,cards,"端末からの削除");description(c,delete,"削除の条件","端末の写真の自動削除には「すべてのファイルへのアクセス」が必要です。端末から削除するのは対象機種のRAWとすべてのJPEGで、クラウドから同じ内容で戻せることを確認し、プレビューを保存した元のファイルに限ります。",true);
    action(c,delete,allFiles,"写真の自動削除に必要なアクセスを許可");description(c,delete,"日付の条件","撮影日から数えます。JPEGで撮影日がない場合は追加日、作成日の順に使います。日付がすべて不明のJPEGは保存確認後に削除対象になり、撮影日不明のRAWは残します。",false);description(c,delete,"アプリを削除したとき","アプリを削除すると端末のプレビューと管理記録も消えます。クラウドの元のファイルはAmazon Photosに残ります。",false);
    View space=new View(c);delete.addView(space,new LinearLayout.LayoutParams(1,dp(c,12)));TextView retention=text(c,"—",15,GalleryUi.TEXT,1.45f);row(c,delete,"保持期間",retention,true);action(c,delete,keep,"保持期間を変更");
    LinearLayout geo=card(c,cards,"原本の位置情報");description(c,geo,null,"撮影時の位置情報も含め、元のファイルを変更せず保存するための許可です。端末の現在位置は取得しません。",true);action(c,geo,location,"位置情報の読み取りを許可");
    line(c,screen);LinearLayout footer=new LinearLayout(c);footer.setGravity(Gravity.CENTER_VERTICAL);footer.setPadding(dp(c,16),dp(c,12),dp(c,16),dp(c,16));
    settings.setText("同期・端末からの削除");settings.setContentDescription("同期・端末からの削除");other.setText("その他");other.setContentDescription("その他");styleButton(c,settings,true,15,52);styleButton(c,other,false,15,52);
    LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,-2,1);left.rightMargin=dp(c,8);footer.addView(settings,left);footer.addView(other,new LinearLayout.LayoutParams(-2,-2));screen.addView(footer);return retention;
  }
  static final class ActionButton extends Button {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    ActionButton(Context c,String label,Runnable action){super(c);setText(label);setMinWidth(0);setMinimumWidth(0);setOnClickListener(v->{GalleryUi.feedback(v);try(DiagnosticLog.Scope ignored=DiagnosticLog.span("action.button")){action.run();}});}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);android.text.Layout layout=getLayout();if(layout==null)return;
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
  static final class ReadOnlySwitch extends Switch {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    ReadOnlySwitch(Context c){super(c);setClickable(false);setFocusable(false);setTextSize(16);setTextColor(GalleryUi.TEXT);setIncludeFontPadding(false);setLineHeight(Math.round(getTextSize()*1.45f));setPadding(0,0,dp(c,70),0);setMinimumHeight(dp(c,48));setMinHeight(dp(c,48));setSingleLine(false);setButtonDrawable(null);setThumbDrawable(null);setTrackDrawable(null);setShowText(false);setSwitchMinWidth(0);setSwitchPadding(0);setBackgroundColor(Color.TRANSPARENT);}
    @Override public void toggle(){} // Values are changed only by the existing confirmation flow.
    @Override public boolean performClick(){return false;}
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setClickable(false);info.setContentDescription(getText()+"。"+(isChecked()?"オン":"オフ")+"。表示のみ。変更は同期・端末からの削除から");}
    @Override protected void onDraw(Canvas c){super.onDraw(c);float d=getResources().getDisplayMetrics().density;int save=c.save();c.translate(getWidth()-dp(getContext(),58),getHeight()/2f-dp(getContext(),10));c.scale(d,d);StatusView.glyph(c,paint,StatusView.Symbol.AUTH,0,2,16,0xffa0a0a4,0);paint.setColor(isChecked()?GalleryUi.TEXT:0xff6b6b70);paint.setStyle(isChecked()?Paint.Style.FILL:Paint.Style.STROKE);paint.setStrokeWidth(1.5f);c.drawRoundRect(22,0,58,20,10,10,paint);paint.setStyle(Paint.Style.FILL);paint.setColor(isChecked()?0xff111111:0xff8a8a8e);c.drawCircle(isChecked()?48:32,10,7,paint);c.restoreToCount(save);}
  }
}
