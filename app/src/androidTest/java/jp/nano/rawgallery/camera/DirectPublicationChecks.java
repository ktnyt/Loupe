package jp.nano.rawgallery.camera;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.UUID;

import jp.nano.rawgallery.PhotoStorage;

/** Exercises the real MediaProvider reservation and history on an isolated virtual Android disk. */
public final class DirectPublicationChecks {
    private DirectPublicationChecks() {}

    public static String run(Instrumentation test) throws Exception {
        require("ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE), "emulator only");
        Context app = test.getTargetContext();
        String volume = MediaStore.getExternalVolumeNames(app).stream()
                .filter(name -> !PhotoStorage.PRIMARY.equals(name))
                .findFirst().orElseThrow(() -> new IOException("No Android virtual external volume"));
        var settings = app.getSharedPreferences("settings", 0);
        String previous = settings.getString("photo_volume", null);
        require(settings.edit().putString("photo_volume", volume).commit(), "select synthetic volume");
        String tag = UUID.randomUUID().toString();
        String dbName = "direct-pub-test-" + tag + ".db";
        Uri published = null;
        try (var ledger = new CameraImportLedger(app, dbName)) {
            CameraPhotoPublisher publisher = new CameraPhotoPublisher(app, ledger, volume, boundary -> {});
            String folder = CameraPhotoPublisher.folder("LoupeDirectTest-" + tag, false);
            byte[] bytes = makeJpeg(0xff6493a0);
            String expected = sha(bytes);
            CameraImportFile source = new CameraImportFile("fixture:" + tag, "DCIM/100TEST/sample.jpg",
                    bytes.length, "synthetic", 1_700_000_000L);
            var held = publisher.reserveDirect(source, expected, expected, bytes.length, "JPEG", folder,
                    "direct-test", () -> false, null);
            require(held.path().startsWith(folder) && held.bytes() == bytes.length, "verified physical path");
            require(ledger.receiptCount() == 0 && !ledger.pending(held.token()).ready, "unpublished state");
            try (var input = app.getContentResolver().openInputStream(held.uri())) {
                require(input != null, "read reserved file");
                byte[] marker = input.readAllBytes();
                require(marker.length == bytes.length, "allocated exact file size");
                held.marker().requireMatch(marker, 0, marker.length, 0);
            }
            writeFixture(app, held.uri(), bytes);
            published = publisher.finishDirect(held, () -> false, () -> {});
            require(ledger.receiptCount() == 1 && expected.equals(ledger.known(source).storedSha),
                    "publish and record only after matching readback");
            try (var input = app.getContentResolver().openInputStream(published)) {
                require(input != null && expected.equals(sha(input.readAllBytes())), "published content");
            }

            byte[] changed = makeJpeg(0xfffeddab);
            String otherSha = sha(changed);
            CameraImportFile other = new CameraImportFile("fixture:" + tag, "DCIM/100TEST/other.jpg",
                    changed.length, "synthetic", 1_700_000_001L);
            var failed = publisher.reserveDirect(other, otherSha, otherSha, changed.length, "JPEG", folder,
                    "direct-test", () -> false, null);
            try {
                publisher.finishDirect(failed, () -> false, () -> {});
                throw new AssertionError("Marker bytes were incorrectly published");
            } catch (IOException expectedMismatch) {
                require(ledger.known(other) == null && !ledger.pending(failed.token()).ready,
                        "mismatch retains an unready record");
            }
            publisher.abandonDirect(failed.token());
            require(ledger.pending(failed.token()) == null && ledger.receiptCount() == 1,
                    "remove only the failed reservation");
            return "PASS direct import: Android-owned reservation, marker, pending URI, readback and receipt";
        } finally {
            try {
                if (published != null) app.getContentResolver().delete(published, null, null);
                app.deleteDatabase(dbName);
            } finally {
                var editor = settings.edit();
                if (previous == null) editor.remove("photo_volume");
                else editor.putString("photo_volume", previous);
                require(editor.commit(), "restore test selection");
            }
        }
    }

    private static void writeFixture(Context app, Uri uri, byte[] bytes) throws IOException {
        try (var output = app.getContentResolver().openOutputStream(uri, "rwt")) {
            if (output == null) throw new IOException("Missing synthetic output");
            output.write(bytes);
        }
    }

    private static byte[] makeJpeg(int color) throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(32, 29, Bitmap.Config.ARGB_8888);
        try {
            bitmap.eraseColor(color);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 87, output)) throw new IOException("JPEG");
            return output.toByteArray();
        } finally {
            bitmap.recycle();
        }
    }

    private static String sha(byte[] data) throws Exception {
        return CameraImportFile.hex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static void require(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }
}
