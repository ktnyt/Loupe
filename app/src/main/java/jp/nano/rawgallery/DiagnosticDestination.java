package jp.nano.rawgallery;

import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;

/** Publishes a completed archive to a location explicitly chosen by the user. */
final class DiagnosticDestination {
  private static final String FILE_NAME = "Loupe-diagnostics.zip";

  static Intent folderPicker(Context context) {
    Intent intent =
        new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra(Intent.EXTRA_LOCAL_ONLY, true);
    PackageManager packages = context.getPackageManager();
    ComponentName target =
        systemFolderPicker(
            packages.queryIntentActivities(
                intent, PackageManager.MATCH_DEFAULT_ONLY | PackageManager.MATCH_SYSTEM_ONLY));
    return target == null ? null : intent.setComponent(target);
  }

  static ComponentName systemFolderPicker(List<ResolveInfo> candidates) {
    ComponentName selected = null;
    int systemFlags = ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
    for (ResolveInfo candidate : candidates) {
      var activity = candidate.activityInfo;
      if (candidate.isCrossProfileIntentForwarderActivity()
          || activity == null
          || !activity.enabled
          || !activity.exported
          || activity.applicationInfo == null
          || !activity.applicationInfo.enabled
          || (activity.applicationInfo.flags & systemFlags) == 0) {
        continue;
      }
      ComponentName component = new ComponentName(activity.packageName, activity.name);
      // Multiple system components are ambiguous. Never delegate the decision to an implicit
      // chooser.
      if (selected != null && !selected.equals(component)) return null;
      selected = component;
    }
    return selected;
  }

  static String downloads(Context context, byte[] archive) throws IOException {
    var resolver = context.getContentResolver();
    ContentValues values = new ContentValues();
    values.put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME);
    values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
    values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
    values.put(MediaStore.MediaColumns.IS_PENDING, 1);
    Uri created =
        resolver.insert(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
    if (created == null) throw new IOException("Diagnostic download not created");
    String location =
        writeNew(
            archive,
            new NewOutput<>() {
              @Override
              public OutputStream open() throws IOException {
                return resolver.openOutputStream(created, "w");
              }

              @Override
              public String publish() throws IOException {
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
                if (resolver.update(created, ready, null, null) != 1)
                  throw new IOException("Diagnostic download not published");
                return "ダウンロード";
              }

              @Override
              public boolean delete() {
                return resolver.delete(created, null, null) == 1;
              }
            });
    return describe(context, created, location);
  }

  static String folder(Context context, Uri tree, byte[] archive) throws IOException {
    String authority = tree.getAuthority();
    if (!"com.android.externalstorage.documents".equals(authority)
        && !"com.android.providers.downloads.documents".equals(authority)) {
      throw new IOException("Unsupported local document provider");
    }
    var provider = context.getPackageManager().resolveContentProvider(authority, 0);
    if (provider == null
        || (provider.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0
        || !DocumentsContract.isTreeUri(tree)) {
      throw new IOException("Invalid local document tree");
    }
    String folderId = DocumentsContract.getTreeDocumentId(tree);
    Uri folder = DocumentsContract.buildDocumentUriUsingTree(tree, folderId);
    String folderName = displayName(context, folder);
    if (folderName == null) throw new IOException("Diagnostic folder unavailable");

    var resolver = context.getContentResolver();
    // The picker grants access to a folder. Only createDocument confers ownership of a new file.
    Uri created =
        DocumentsContract.createDocument(
            resolver,
            folder,
            "application/octet-stream",
            ".Loupe-diagnostics-" + UUID.randomUUID() + ".part");
    if (created == null
        || !authority.equals(created.getAuthority())
        || !DocumentsContract.isDocumentUri(context, created)
        || folderId.equals(DocumentsContract.getDocumentId(created))) {
      throw new IOException("Diagnostic document not created");
    }

    Uri published =
        writeNew(
            archive,
            new NewOutput<>() {
              @Override
              public OutputStream open() throws IOException {
                return resolver.openOutputStream(created, "w");
              }

              @Override
              public Uri publish() throws IOException {
                Uri renamed = DocumentsContract.renameDocument(resolver, created, FILE_NAME);
                if (renamed == null) throw new IOException("Diagnostic document not published");
                return renamed;
              }

              @Override
              public boolean delete() throws IOException {
                return DocumentsContract.deleteDocument(resolver, created);
              }
            });
    return describe(context, published, folderName);
  }

  private static String describe(Context context, Uri file, String folderName) {
    String filename = displayName(context, file);
    return filename == null ? folderName : folderName + "\n" + filename;
  }

  private static String displayName(Context context, Uri uri) {
    try (var row =
        context
            .getContentResolver()
            .query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      return row != null && row.moveToFirst() ? row.getString(0) : null;
    } catch (RuntimeException unavailable) {
      return null;
    }
  }

  interface NewOutput<T> {
    OutputStream open() throws IOException;

    T publish() throws IOException;

    boolean delete() throws IOException;
  }

  static final class CleanupFailed extends IOException {
    CleanupFailed(Exception cause) {
      super("Incomplete diagnostic output could not be removed", cause);
    }
  }

  static <T> T writeNew(byte[] archive, NewOutput<T> output) throws IOException {
    try {
      try (OutputStream stream = output.open()) {
        if (stream == null) throw new IOException("Diagnostic output unavailable");
        stream.write(archive);
      }
      return output.publish();
    } catch (IOException | RuntimeException failure) {
      try {
        if (!output.delete()) throw new IOException("Incomplete diagnostic output not removed");
      } catch (IOException | RuntimeException cleanup) {
        failure.addSuppressed(cleanup);
        throw new CleanupFailed(failure);
      }
      throw failure;
    }
  }
}
