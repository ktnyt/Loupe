package jp.nano.rawgallery.camera;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.IOException;
import java.util.function.BooleanSupplier;
import jp.nano.rawgallery.PhotoStorage;

/** Previously verified destination metadata used only for SD duplicate suppression. */
record SdSavedPhotoRevision(String uri, String storedSha, long storedBytes, String epoch,
        long generationAdded, long generationModified) {
    private static final String[] FIELDS = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.VOLUME_NAME,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.IS_TRASHED,
            MediaStore.MediaColumns.GENERATION_ADDED, MediaStore.MediaColumns.GENERATION_MODIFIED};

    static boolean eligibleSource(CameraImportFile source) {
        if (!source.sourceId.matches("original-byte-v1:sd-saf:[a-f0-9]{64}")) return false;
        try { return Long.parseLong(source.sourceStamp) > 0; }
        catch (NumberFormatException unknown) { return false; }
    }

    boolean matchesContent(CameraImportLedger.Content content) {
        return uri.equals(content.uri) && storedSha.equals(content.storedSha) && storedBytes == content.storedBytes;
    }

    static SdSavedPhotoRevision capture(Context context, CameraImportLedger.Content content, BooleanSupplier stopped)
            throws IOException {
        if (!CameraImportHistoryPlan.validUri(content.uri)) return null;
        Uri uri = Uri.parse(content.uri);
        String volume = MediaStore.getVolumeName(uri);
        if (volume == null || MediaStore.VOLUME_EXTERNAL.equals(volume)) return null;
        return CameraDocumentSource.boundedMetadata(stopped, signal -> {
            try {
                PhotoStorage.requireMounted(context, uri);
                String before = MediaStore.getVersion(context, volume);
                Bundle args = new Bundle();
                args.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
                args.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);
                ContentResolver resolver = context.getContentResolver();
                try (var file = resolver.openFileDescriptor(uri, "r", signal)) {
                    if (file == null) return null;
                    var stat = Os.fstat(file.getFileDescriptor());
                    if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size != content.storedBytes) return null;
                }
                SdSavedPhotoRevision observed;
                try (Cursor cursor = resolver.query(uri, FIELDS, args, signal)) {
                    observed = observed(cursor, content, ContentUris.parseId(uri), volume, context.getPackageName(), before);
                }
                return observed != null && before.equals(MediaStore.getVersion(context, volume)) ? observed : null;
            } catch (ErrnoException | IOException | RuntimeException incomplete) {
                // Unknown here only declines the shortcut. The normal byte path handles status errors.
                return null;
            }
        });
    }

    static SdSavedPhotoRevision observed(Cursor row, CameraImportLedger.Content content, long expectedId,
            String volume, String owner, String epoch) throws IOException {
        if (epoch == null || epoch.isEmpty() || !CameraPhotoPublisher.moveToOnlyMediaRow(row)) return null;
        int[] columns = new int[FIELDS.length];
        for (int i = 0; i < FIELDS.length; i++) {
            int index = row.getColumnIndex(FIELDS[i]);
            int type = i == 1 || i == 2 ? Cursor.FIELD_TYPE_STRING : Cursor.FIELD_TYPE_INTEGER;
            if (index < 0 || row.getType(index) != type) return null;
            columns[i] = index;
        }
        long added = row.getLong(columns[6]);
        long modified = row.getLong(columns[7]);
        if (row.getLong(columns[0]) != expectedId || !volume.equals(row.getString(columns[1]))
                || !owner.equals(row.getString(columns[2])) || row.getLong(columns[3]) != content.storedBytes
                || row.getLong(columns[4]) != 0 || row.getLong(columns[5]) != 0 || added < 0 || modified < added)
            return null;
        return new SdSavedPhotoRevision(content.uri, content.storedSha, content.storedBytes, epoch, added, modified);
    }
}
