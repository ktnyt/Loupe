package jp.nano.rawgallery;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.widget.TextView;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.ArrayList;

/** Test APK recipient: reads only granted synthetic JPEGs and never sends or saves them. */
public final class ShareHistoryTargetActivity extends Activity {
  static final int FIRST_WIDTH = 32;
  static final int HEIGHT = 24;
  static final int SINGLE_WIDTH = FIRST_WIDTH + 3;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    ArrayList<Uri> uris = new ArrayList<>();
    Intent intent = getIntent();
    boolean multiple = Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction());
    boolean single = Intent.ACTION_SEND.equals(intent.getAction());
    if (multiple) {
      ArrayList<Uri> supplied = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
      if (supplied != null) uris.addAll(supplied);
    } else if (single) {
      Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
      if (uri != null) uris.add(uri);
    }
    ClipData clip = intent.getClipData();
    int flags = intent.getFlags();
    boolean valid =
        ((single && uris.size() == 1) || (multiple && uris.size() == 2))
            && "image/jpeg".equals(intent.getType())
            && (flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
            && (flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0
            && clip != null
            && clip.getItemCount() == uris.size();
    for (int i = 0; valid && i < uris.size(); i++) {
      Uri uri = uris.get(i);
      valid =
          uri != null
              && "content".equals(uri.getScheme())
              && "jp.nano.rawgallery.share".equals(uri.getAuthority())
              && uri.equals(clip.getItemAt(i).getUri())
              && realReadOnlyGrant(uri)
              && readFixtureJpeg(uri, single ? SINGLE_WIDTH : FIRST_WIDTH + i);
    }
    TextView receipt = new TextView(this);
    receipt.setText("Loupe test receipt " + uris.size() + " " + (valid ? "OK" : "FAILED"));
    receipt.setTextSize(20);
    receipt.setGravity(android.view.Gravity.CENTER);
    setContentView(receipt);
  }

  private boolean realReadOnlyGrant(Uri uri) {
    int pid = Process.myPid(), uid = Process.myUid();
    return checkUriPermission(uri, pid, uid, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            == PackageManager.PERMISSION_GRANTED
        && checkUriPermission(uri, pid, uid, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            == PackageManager.PERMISSION_DENIED;
  }

  private boolean readFixtureJpeg(Uri uri, int width) {
    try (InputStream source = getContentResolver().openInputStream(uri)) {
      if (source == null) return false;
      BufferedInputStream input = new BufferedInputStream(source);
      input.mark(2);
      if (input.read() != 255 || input.read() != 216) return false;
      input.reset();
      BitmapFactory.Options bounds = new BitmapFactory.Options();
      bounds.inJustDecodeBounds = true;
      BitmapFactory.decodeStream(input, null, bounds);
      // Distinct synthetic widths witness selection order even if both URI containers are reversed.
      return bounds.outWidth == width && bounds.outHeight == HEIGHT;
    } catch (Exception error) {
      return false;
    }
  }
}
