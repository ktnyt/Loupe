package jp.nano.rawgallery;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.MainThread;
import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;

/** Owns the verified private cache file between download and the choice of a SAF destination. */
@MainThread
final class E2RestorePicker {
  private E2Photos.PreparedRestore waiting;

  E2RestorePicker(File cache, Bundle saved) {
    if (saved == null) return;
    String directory = saved.getString("e2-verified-directory", "");
    if (!directory.matches("e2-export-[0-9]+")) return;
    File file = new File(new File(cache, directory), "original");
    try {
      var restored =
          new E2Photos.PreparedRestore(
              file, saved.getLong("e2-verified-size"), saved.getString("e2-verified-sha"));
      E2Photos.holdForPicker(file);
      waiting = restored;
    } catch (IOException | IllegalArgumentException unavailable) {
      // The cached file can be fetched again from the cloud catalog.
    }
  }

  boolean isWaiting() {
    return waiting != null;
  }

  void saveState(Bundle saved) {
    if (waiting == null) return;
    saved.putString("e2-verified-directory", waiting.file().getParentFile().getName());
    saved.putLong("e2-verified-size", waiting.size());
    saved.putString("e2-verified-sha", waiting.sha());
  }

  /** Takes ownership of a file already leased by the download worker. */
  boolean open(
      E2Photos.PreparedRestore file, String mime, String name, Consumer<Intent> launch) {
    if (waiting != null) {
      if (!waiting.file().equals(file.file())) E2Photos.discard(file.file());
      return false;
    }
    waiting = file;
    Intent intent =
        new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mime)
            .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            .putExtra(Intent.EXTRA_TITLE, name);
    try {
      launch.accept(intent);
      return true;
    } catch (ActivityNotFoundException | SecurityException unavailable) {
      cancel();
      return false;
    }
  }

  E2Photos.PreparedRestore take() {
    E2Photos.PreparedRestore file = waiting;
    waiting = null;
    return file;
  }

  void cancel() {
    E2Photos.PreparedRestore file = take();
    if (file != null) E2Photos.discard(file.file());
  }

  void close(boolean finishing, boolean changingConfigurations) {
    E2Photos.PreparedRestore file = take();
    if (file == null) return;
    if (finishing) E2Photos.discard(file.file());
    else if (!changingConfigurations) E2Photos.releasePickerOwnership(file.file());
  }
}
