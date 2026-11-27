package jp.nano.rawgallery;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.widget.TextView;
import java.io.InputStream;

/** Separate test APK recipient for the real read-only URI permission check. */
public final class PhotoEditTargetActivity extends Activity {
  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    Uri uri = getIntent().getData();
    boolean readable = false;
    if (uri != null && "jp.nano.rawgallery.share".equals(uri.getAuthority())) {
      try (InputStream input = getContentResolver().openInputStream(uri)) {
        readable =
            input != null
                && input.read() == 255
                && input.read() == 216
                && checkUriPermission(
                        uri,
                        Process.myPid(),
                        Process.myUid(),
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    == PackageManager.PERMISSION_DENIED;
      } catch (Exception ignored) {
      }
    }
    TextView result = new TextView(this);
    result.setText(readable ? "Loupe editor receipt OK" : "Loupe editor receipt FAILED");
    setContentView(result);
  }
}
