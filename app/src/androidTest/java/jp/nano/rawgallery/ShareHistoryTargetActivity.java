package jp.nano.rawgallery;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import java.io.InputStream;
import java.util.ArrayList;

/** Test APK recipient: reads only granted synthetic JPEGs and never sends or saves them. */
public final class ShareHistoryTargetActivity extends Activity {
  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    ArrayList<Uri> uris=new ArrayList<>();
    Intent intent=getIntent();
    if(Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
      ArrayList<Uri> supplied=intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
      if(supplied!=null)uris.addAll(supplied);
    } else {Uri uri=intent.getParcelableExtra(Intent.EXTRA_STREAM);if(uri!=null)uris.add(uri);}
    boolean valid=!uris.isEmpty();
    for(Uri uri:uris) {
      if(!"content".equals(uri.getScheme())||!"jp.nano.rawgallery.share".equals(uri.getAuthority())){valid=false;continue;}
      try(InputStream input=getContentResolver().openInputStream(uri)){valid&=input!=null&&input.read()==255&&input.read()==216;}
      catch(Exception error){valid=false;}
    }
    TextView receipt=new TextView(this);receipt.setText("Loupe test receipt "+uris.size()+" "+(valid?"OK":"FAILED"));receipt.setTextSize(20);receipt.setGravity(android.view.Gravity.CENTER);setContentView(receipt);
  }
}
