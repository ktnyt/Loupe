package jp.nano.rawgallery;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.FrameLayout;
import androidx.appcompat.app.AppCompatActivity;
import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import jp.nano.rawgallery.camera.CameraCredentialStore;
import jp.nano.rawgallery.camera.CameraQrCredentials;

/** Internal scanner stores locally and returns only a non-sensitive outcome code. */
public final class CameraQrScanActivity extends AppCompatActivity {
  static final int SAVED = 1, INVALID = 2, SAVE_FAILED = 3, DENIED = 4, CAMERA_FAILED = 5;
  private DecoratedBarcodeView scanner;
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private boolean saving;

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow()
        .addFlags(
            WindowManager.LayoutParams.FLAG_SECURE
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    FrameLayout frame = new FrameLayout(this);
    scanner = new DecoratedBarcodeView(this);
    scanner
        .getBarcodeView()
        .addStateListener(
            new com.journeyapps.barcodescanner.CameraPreview.StateListener() {
              public void previewSized() {}

              public void previewStarted() {}

              public void previewStopped() {}

              public void cameraClosed() {}

              public void cameraError(Exception error) {
                if (!saving) {
                  setResult(CAMERA_FAILED);
                  finish();
                }
              }
            });
    scanner
        .getBarcodeView()
        .setDecoderFactory(
            new DefaultDecoderFactory(Collections.singletonList(BarcodeFormat.QR_CODE)));
    scanner.setStatusText("カメラに表示されたQRコードを読み取ってください");
    frame.addView(scanner);
    setContentView(frame);
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[] {Manifest.permission.CAMERA}, 409);
    else begin();
  }

  private void begin() {
    scanner.decodeSingle(
        new BarcodeCallback() {
          @Override
          public void barcodeResult(BarcodeResult result) {
            if (saving || result == null || result.getText() == null) return;
            saving = true;
            scanner.pause();
            scanner.setStatusText("登録しています…");
            String raw = result.getText();
            io.execute(
                () -> {
                  int outcome;
                  try (CameraQrCredentials credentials = CameraQrCredentials.parse(raw)) {
                    new CameraCredentialStore(getApplicationContext()).save(credentials);
                    outcome = SAVED;
                  } catch (IllegalArgumentException failure) {
                    outcome = INVALID;
                  } catch (Exception failure) {
                    outcome = SAVE_FAILED;
                  }
                  int resultCode = outcome;
                  runOnUiThread(
                      () -> {
                        if (!isFinishing() && !isDestroyed()) {
                          setResult(resultCode);
                          finish();
                        }
                      });
                });
          }
        });
    scanner.resume();
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(request, permissions, results);
    if (request == 409) {
      if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        begin();
      else {
        setResult(DENIED);
        finish();
      }
    }
  }

  @Override
  public void onBackPressed() {
    if (!saving) super.onBackPressed();
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (!saving
        && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
      scanner.resume();
  }

  @Override
  protected void onPause() {
    scanner.pause();
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    io.shutdown();
    super.onDestroy();
  }
}
