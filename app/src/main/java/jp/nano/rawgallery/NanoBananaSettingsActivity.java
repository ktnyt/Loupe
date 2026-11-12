package jp.nano.rawgallery;

import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textfield.TextInputEditText;
import java.io.IOException;

/** Registers credentials and named prompts; no API requests are made by this screen. */
public final class NanoBananaSettingsActivity extends AppCompatActivity {
  private NanoBananaStore store;
  private LinearLayout column;
  private TextView credential;
  @Override public void onCreate(Bundle state) {
    super.onCreate(state); getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    store=new NanoBananaStore(this); render();
  }
  private int dp(int value) { return GalleryUi.dp(this,value); }
  private void render() {
    ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true);
    column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);
    column.setPadding(dp(20),dp(16),dp(20),dp(24));scroll.addView(column);
    scroll.setOnApplyWindowInsetsListener((v,insets)->{
      android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());
      v.setPadding(i.left,i.top,i.right,i.bottom);return insets;
    });
    TextView title=label("Nano Banana",24);title.setAccessibilityHeading(true);column.addView(title);
    column.addView(label("NanoBananaAPI.aiへ写真とプロンプトを送信します（従量課金）。",14));
    credential=label(store.hasKey()?"APIキー登録済み":"APIキー未登録",16);column.addView(credential);
    action(store.hasKey()?"APIキーを変更":"APIキーを登録",this::editKey);
    if(store.hasKey()) action("APIキーを削除",()->new MaterialAlertDialogBuilder(this).setTitle("APIキーを削除しますか？")
        .setNegativeButton("キャンセル",null).setPositiveButton("削除",(d,w)->{try{store.deleteKey();render();}catch(IOException e){error(e);}}).show());
    column.addView(label(store.hasE2()?"IDrive e2登録済み（東京）":"IDrive e2未登録（東京）",16));
    action(store.hasE2()?"IDrive e2を変更":"IDrive e2を登録",this::editE2);
    if(store.hasE2())action("IDrive e2の設定を削除",()->new MaterialAlertDialogBuilder(this).setTitle("e2設定を削除しますか？")
        .setNegativeButton("キャンセル",null).setPositiveButton("削除",(d,w)->{try{store.deleteE2();render();}catch(IOException e){error(e);}}).show());
    try {if(store.job()!=null)action("進行中の処理・前回の結果",()->startActivity(new android.content.Intent(this,NanoBananaActivity.class)));}catch(IOException e){error(e);}
    column.addView(label("e2バケットはバージョニング有効が必要です。送信用の一時画像は、生成後に削除します。",14));
    TextView prompts=label("プロンプト",20);prompts.setAccessibilityHeading(true);column.addView(prompts);
    try {
      for(NanoBananaStore.Prompt p:store.prompts()) action(p.name,()->editPrompt(p));
    } catch(IOException e) { error(e); }
    action("プロンプトを追加",()->editPrompt(null));
    action("閉じる",this::finish);setContentView(scroll);
  }
  private TextView label(String text,int size) {
    TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setTextColor(GalleryUi.TEXT);
    v.setPadding(0,dp(12),0,dp(12));return v;
  }
  private void action(String text,Runnable run) {
    Button b=MaterialUi.button(this);b.setText(text);b.setAllCaps(false);b.setMinHeight(dp(48));
    b.setOnClickListener(v->run.run());column.addView(b,new LinearLayout.LayoutParams(-1,-2));
  }
  private LinearLayout form() {
    LinearLayout f=new LinearLayout(this);f.setOrientation(LinearLayout.VERTICAL);f.setPadding(dp(20),dp(8),dp(20),dp(8));return f;
  }
  private TextInputEditText field(LinearLayout parent,String hint,String text,int max,boolean secret) {
    TextInputLayout container=new TextInputLayout(this);container.setHint(hint);
    TextInputEditText input=new TextInputEditText(this);input.setText(text);input.setSaveEnabled(false);
    input.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
    input.setInputType(secret?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:
        InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});
    input.setMinLines(secret?1:2);input.setMaxLines(secret?1:8);
    container.addView(input,new LinearLayout.LayoutParams(-1,-2));parent.addView(container);return input;
  }
  private void editKey() {
    LinearLayout f=form();TextInputEditText input=field(f,"NanoBananaAPI.ai APIキー","",512,true);
    androidx.appcompat.app.AlertDialog dialog=new MaterialAlertDialogBuilder(this).setTitle("APIキーを登録")
        .setView(f).setNegativeButton("キャンセル",null).setPositiveButton("保存",null).create();
    dialog.getWindow();dialog.setOnShowListener(d->{dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
      dialog.getButton(-1).setOnClickListener(v->{try{store.setKey(input.getText().toString().trim());input.setText("");dialog.dismiss();render();}catch(IOException e){input.setError(e.getMessage());}});
    });dialog.setOnDismissListener(d->input.setText(""));dialog.show();
  }
  private void editE2() {
    LinearLayout f=form();
    TextInputEditText bucket=field(f,"e2バケット名（東京・バージョニング有効）","",63,false);
    TextInputEditText access=field(f,"アクセスキーID","",20,true);
    TextInputEditText secret=field(f,"シークレットアクセスキー","",40,true);
    ScrollView scroll=new ScrollView(this);scroll.addView(f);
    androidx.appcompat.app.AlertDialog dialog=new MaterialAlertDialogBuilder(this).setTitle("IDrive e2を登録")
        .setView(scroll).setNegativeButton("キャンセル",null).setPositiveButton("保存",null).create();
    dialog.setOnShowListener(d->{dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
      dialog.getButton(-1).setOnClickListener(v->{try{
        store.setE2(bucket.getText().toString().trim(),access.getText().toString().trim(),secret.getText().toString().trim());
        access.setText("");secret.setText("");dialog.dismiss();render();
      }catch(IOException e){secret.setError(e.getMessage());}});
    });dialog.setOnDismissListener(d->{access.setText("");secret.setText("");});dialog.show();
  }
  private void editPrompt(NanoBananaStore.Prompt p) {
    LinearLayout f=form();TextInputEditText name=field(f,"メニューに表示する名前",p==null?"":p.name,100,false);
    TextInputEditText text=field(f,"プロンプト",p==null?"":p.text,10000,false);
    ScrollView scroll=new ScrollView(this);scroll.addView(f);
    MaterialAlertDialogBuilder builder=new MaterialAlertDialogBuilder(this).setTitle(p==null?"プロンプトを追加":"プロンプトを編集")
        .setView(scroll).setNegativeButton("キャンセル",null).setPositiveButton("保存",null);
    if(p!=null)builder.setNeutralButton("削除",(d,w)->new MaterialAlertDialogBuilder(this).setTitle("このプロンプトを削除しますか？")
        .setNegativeButton("キャンセル",null).setPositiveButton("削除",(dd,ww)->{try{store.deletePrompt(p.id);render();}catch(IOException e){error(e);}}).show());
    androidx.appcompat.app.AlertDialog dialog=builder.create();
    dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{
      store.savePrompt(p==null?null:p.id,name.getText().toString(),text.getText().toString());dialog.dismiss();render();
    }catch(IOException e){text.setError(e.getMessage());}}));dialog.show();
  }
  private void error(IOException e) {new MaterialAlertDialogBuilder(this).setMessage(e.getMessage()).setPositiveButton("閉じる",null).show();}
}
