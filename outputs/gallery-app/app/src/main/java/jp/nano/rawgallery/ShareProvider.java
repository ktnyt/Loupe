package jp.nano.rawgallery;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Exposes only random-named files in this app's share directory, under read-only URI grants. */
public final class ShareProvider extends ContentProvider {
  public boolean onCreate() {
    return true;
  }

  private File file(Uri uri) throws FileNotFoundException {
    if (!"jp.nano.rawgallery.share".equals(uri.getAuthority()) || uri.getPathSegments().size() != 1)
      throw new FileNotFoundException();
    String name = uri.getLastPathSegment();
    if (name == null || !name.matches("[a-f0-9-]{36}\\.(jpg|dng|orf)"))
      throw new FileNotFoundException();
    File f = new File(new File(getContext().getCacheDir(), "shares"), name);
    if (!f.isFile()) throw new FileNotFoundException();
    return f;
  }

  public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
    return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
  }

  public String getType(Uri uri) {
    String s = uri.toString();
    return s.endsWith(".jpg")
        ? "image/jpeg"
        : s.endsWith(".orf") ? "image/x-olympus-orf" : "image/x-adobe-dng";
  }

  public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
    try {
      File f = file(uri);
      String[] cols =
          projection == null
              ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
              : projection;
      MatrixCursor c = new MatrixCursor(cols);
      Object[] row = new Object[cols.length];
      for (int i = 0; i < cols.length; i++) {
        if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
        else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
      }
      c.addRow(row);
      return c;
    } catch (FileNotFoundException e) {
      return null;
    }
  }

  public Uri insert(Uri u, ContentValues v) {
    throw new UnsupportedOperationException();
  }

  public int delete(Uri u, String s, String[] a) {
    throw new UnsupportedOperationException();
  }

  public int update(Uri u, ContentValues v, String s, String[] a) {
    throw new UnsupportedOperationException();
  }
}
