package jp.nano.rawgallery;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Process;
import android.os.SystemClock;
import android.provider.MediaStore;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/** Opt-in mixed-media round trip only on a dedicated emulator and loupe-test. */
final class E2MixedMediaChecks {
  private static final String BUCKET = "loupe-test";
  private record Sample(String name, String mime, byte[] contents) {}

  static String run(Instrumentation test, String operation, String token) throws Exception {
    Context context = test.getTargetContext();
    if (!"jp.nano.rawgallery.e2verify".equals(context.getPackageName()))
      throw new AssertionError("Requires the verification app");
    File input = new File(context.getCacheDir(), "e2-background-credentials");
    List<String> config;
    try {
      if (input.length() > 512) throw new AssertionError("Invalid test credentials input");
      config = Files.readAllLines(input.toPath());
    } finally {
      Files.deleteIfExists(input.toPath());
    }
    String current = shell(test, "am get-current-user");
    if (!"1".equals(shell(test, "getprop ro.kernel.qemu"))
        || !current.matches("[1-9][0-9]{0,3}") || Process.myUid() / 100000 != Integer.parseInt(current)
        || token == null || !token.matches("[0-9a-f]{12}")
        || !E2AutoSync.candidates(context).isEmpty() || !E2Settings.configuredBucket(context).isEmpty())
      throw new AssertionError("Requires an empty verification app in a dedicated emulator user");
    if (config.size() != 3 || !BUCKET.equals(config.get(0))) throw new AssertionError("Requires loupe-test");
    List<Sample> samples = samples(test, token);
    try (E2Photos photos = new E2Photos(context)) {
      photos.settings.connect(BUCKET, config.get(1), config.get(2));
      switch (operation) {
        case "backup":
          for (Sample sample : samples) seed(context, sample);
          List<E2AutoSync.Candidate> candidates = E2AutoSync.candidates(context);
          if (candidates.size() != samples.size()) throw new AssertionError("A media format was not discovered");
          showConsent(test, context, true);
          shell(test, "am start --user " + current + " -a android.settings.SETTINGS");
          shell(test, "svc wifi enable");
          long deadline = SystemClock.elapsedRealtime() + 100000;
          JobState state;
          do {
            state = E2SyncJob.snapshot(context);
            if (state.kind == JobState.Kind.FAILED || state.kind == JobState.Kind.PARTIAL
                || state.kind == JobState.Kind.AUTH_REQUIRED)
              throw new AssertionError("Mixed media job failed: " + state.kind);
            if (state.kind == JobState.Kind.COMPLETE) break;
            SystemClock.sleep(250);
          } while (SystemClock.elapsedRealtime() < deadline);
          if (state.kind != JobState.Kind.COMPLETE || state.ready != samples.size() || state.failed != 0)
            throw new AssertionError("Expected all three originals and catalog published by JobScheduler");
          verify(photos, E2RecoveryCatalog.latest(photos.settings.archive(BUCKET), BUCKET), samples);
          checkScheduledCatalog(photos, context);
          break;
        case "consent":
          showConsent(test, context, false);
          return "PASS mixed media consent: format and destination are displayed";
        case "restore":
          if (!photos.settings.records.all().isEmpty()) throw new AssertionError("Old device records present");
          verify(photos, photos.loadCatalog(BUCKET), samples);
          if (!photos.settings.records.all().isEmpty()) throw new AssertionError("Imported a local backup receipt");
          break;
        default:
          throw new AssertionError("Unknown mixed media trial operation");
      }
      return "PASS mixed media " + operation + ": JPEG, DNG and MP4 matched original bytes";
    } finally {
      E2SyncJob.setEnabled(context, false);
      if ("backup".equals(operation) || "consent".equals(operation)) {
        shell(test, "svc wifi enable");
        shell(test, "svc data enable");
      }
      context.getSharedPreferences("e2_connection", Context.MODE_PRIVATE).edit().clear().commit();
    }
  }

  private static void checkScheduledCatalog(E2Photos photos, Context context) throws Exception {
    long revision = photos.settings.records.indexRevision();
    if (E2AutoSync.needsCatalog(photos, BUCKET, false, null))
      throw new AssertionError("Unchanged backup would read the catalog");
    if (!E2AutoSync.needsCatalog(photos, BUCKET, true, null)
        || !E2AutoSync.needsCatalog(photos, BUCKET, true, revision - 1)
        || E2AutoSync.needsCatalog(photos, BUCKET, true, revision))
      throw new AssertionError("Cleanup must obtain a catalog unless this run has a current snapshot");

    JSONObject saved = photos.settings.records.all().stream()
        .filter(row -> row.optString("name").endsWith(".jpg")).findFirst().orElseThrow();
    String syntheticKey = PhotoRepository.digest("catalog-gate-test:" + saved.getString("key"))
        .substring(0, 32);
    JSONObject foreign = new JSONObject(saved.toString()).put("key", syntheticKey)
        .put("bucket", "loupe-catalog-other-test");
    try (E2Photos reopened = new E2Photos(context)) {
      try {
        for (String state : List.of(E2Cleanup.RELEASING, E2Cleanup.RELEASED, "verified")) {
          reopened.settings.records.put(saved, state);
          if (E2AutoSync.needsCatalog(reopened, BUCKET, false, null))
            throw new AssertionError("A cleanup-state change would read the catalog: " + state);
        }
        reopened.settings.records.put(foreign, "verified");
        if (E2AutoSync.needsCatalog(reopened, BUCKET, false, null))
          throw new AssertionError("A write for another bucket would read the catalog");
        reopened.settings.records.put(foreign.put("bucket", BUCKET), "verified");
        if (!E2AutoSync.needsCatalog(reopened, BUCKET, false, null))
          throw new AssertionError("A new verified receipt was treated as published");
        reopened.settings.records.getWritableDatabase().delete("photos", "key=?", new String[]{syntheticKey});

        JSONObject changed = new JSONObject(saved.toString()).put("version", "different-version");
        reopened.settings.records.put(changed, "verified");
        if (!E2AutoSync.needsCatalog(reopened, BUCKET, false, null))
          throw new AssertionError("A different version was treated as published");
      } finally {
        reopened.settings.records.getWritableDatabase().delete("photos", "key=?", new String[]{syntheticKey});
        reopened.settings.records.put(saved, "verified");
      }
      if (E2AutoSync.needsCatalog(reopened, BUCKET, false, null))
        throw new AssertionError("The acknowledged catalog would be read again");
    }
  }

  private static void showConsent(Instrumentation test, Context context, boolean accept) throws Exception {
    Activity activity = test.startActivitySync(new Intent(context, E2SyncActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    shell(test, "svc data disable");
    shell(test, "svc wifi disable");
    long deadline = SystemClock.elapsedRealtime() + 20000;
    boolean consentOpened = false;
    while (SystemClock.elapsedRealtime() < deadline && !consentOpened) {
      test.runOnMainSync(() -> {
        View button = startButton(activity.getWindow().getDecorView());
        if (button != null && button.isEnabled()) button.performClick();
      });
      SystemClock.sleep(200);
      AccessibilityNodeInfo dialog = test.getUiAutomation().getRootInActiveWindow();
      consentOpened = dialog != null && !dialog.findAccessibilityNodeInfosByText(
          "自動バックアップを開始しますか？").isEmpty();
    }
    if (!consentOpened) throw new AssertionError("No scope confirmation appeared");
    SystemClock.sleep(500);
    Bitmap screenshot = test.getUiAutomation().takeScreenshot();
    if (screenshot == null) throw new AssertionError("Consent screenshot unavailable");
    try (OutputStream output = Files.newOutputStream(new File(context.getCacheDir(),
        "e2-mixed-consent.png").toPath())) {
      if (!screenshot.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new AssertionError();
    } finally { screenshot.recycle(); }
    AccessibilityNodeInfo dialog = test.getUiAutomation().getRootInActiveWindow();
    if (dialog == null || dialog.findAccessibilityNodeInfosByText("RAW・JPEG・動画").isEmpty()
        || dialog.findAccessibilityNodeInfosByText(BUCKET).isEmpty()
        || dialog.findAccessibilityNodeInfosByText("保存済みの原本もクラウドの復元一覧に反映します。").isEmpty())
      throw new AssertionError("Missing scope description");
    if (!accept) return;
    boolean accepted = false;
    for (AccessibilityNodeInfo action : dialog.findAccessibilityNodeInfosByText("開始")) {
      if ("開始".contentEquals(String.valueOf(action.getText())) && action.isEnabled()
          && action.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
        accepted = true;
        break;
      }
    }
    long acceptedBy = SystemClock.elapsedRealtime() + 5000;
    while (accepted && !E2SyncJob.fullMediaConsent(context) && SystemClock.elapsedRealtime() < acceptedBy)
      SystemClock.sleep(100);
    if (!accepted || !E2SyncJob.fullMediaConsent(context))
      throw new AssertionError("Mixed-media consent did not enable backup");
  }

  private static View startButton(View view) {
    if (view instanceof TextView text && view.isClickable() && "開始".contentEquals(text.getText()))
      return view;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        View result = startButton(group.getChildAt(i));
        if (result != null) return result;
      }
    return null;
  }

  private static void verify(E2Photos photos, E2RecoveryCatalog.Snapshot snapshot,
      List<Sample> samples) throws Exception {
    if (snapshot == null || snapshot.previous()) throw new AssertionError("Missing latest catalog");
    for (Sample sample : samples) {
      Map<String, JSONObject> rows = snapshot.records();
      List<JSONObject> matches = new java.util.ArrayList<>();
      for (JSONObject row : rows.values()) if (sample.name().equals(row.optString("name"))) matches.add(row);
      if (matches.size() != 1) throw new AssertionError("Expected exactly one record for " + sample.name());
      JSONObject record = matches.get(0);
      if (!sample.mime().equals(record.getString("mime"))) throw new AssertionError("Wrong restore MIME");
      File original = photos.downloadRecord(record);
      try {
        if (!Arrays.equals(sample.contents(), Files.readAllBytes(original.toPath())))
          throw new AssertionError("Original bytes mismatch for " + sample.name());
      } finally { E2Photos.discard(original); }
    }
  }

  private static void seed(Context context, Sample sample) throws Exception {
    var resolver = context.getContentResolver();
    boolean video = sample.mime().startsWith("video/");
    ContentValues fields = new ContentValues();
    fields.put(MediaStore.MediaColumns.DISPLAY_NAME, sample.name());
    fields.put(MediaStore.MediaColumns.MIME_TYPE, sample.mime());
    fields.put(MediaStore.MediaColumns.RELATIVE_PATH, video ? "Movies/LoupeE2Mixed/" : "Pictures/LoupeE2Mixed/");
    fields.put(MediaStore.MediaColumns.IS_PENDING, 1);
    Uri uri = resolver.insert(video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, fields);
    if (uri == null) throw new AssertionError("Unable to seed mixed media");
    try (OutputStream out = resolver.openOutputStream(uri)) {
      if (out == null) throw new AssertionError("Failed to write fixture");
      out.write(sample.contents());
    }
    fields.clear();
    fields.put(MediaStore.MediaColumns.IS_PENDING, 0);
    if (resolver.update(uri, fields, null, null) != 1) throw new AssertionError("Fixture remained pending");
  }

  private static List<Sample> samples(Instrumentation test, String token) throws Exception {
    String prefix = "loupe-mixed-" + token;
    ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
    Bitmap image = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
    try {
      image.eraseColor(0xff224488);
      if (!image.compress(Bitmap.CompressFormat.JPEG, 95, jpeg)) throw new AssertionError("JPEG encoder failed");
    } finally { image.recycle(); }
    try (InputStream video = test.getContext().getAssets().open("video-synthetic.mp4")) {
      return List.of(new Sample(prefix + ".jpg", "image/jpeg", jpeg.toByteArray()),
          new Sample(prefix + ".dng", "image/x-adobe-dng", raw()),
          new Sample(prefix + ".mp4", "video/mp4", video.readAllBytes()));
    }
  }

  private static byte[] raw() {
    // A small synthetic Bayer image without an embedded preview, in a little-endian TIFF/DNG.
    int edge = 16, pixels = edge * edge * Short.BYTES;
    int[][] fields = {
        {256, 4, 1, edge}, {257, 4, 1, edge}, {258, 3, 1, 16}, {259, 3, 1, 1},
        {262, 3, 1, 32803}, {273, 4, 1, 0}, {277, 3, 1, 1}, {278, 4, 1, edge},
        {279, 4, 1, pixels}, {284, 3, 1, 1}, {33421, 3, 2, 0x00020002},
        {33422, 1, 4, 0x02010100}, {50706, 1, 4, 0x00000401}, {50717, 4, 1, 65535}
    };
    int data = 8 + Short.BYTES + fields.length * 12 + Integer.BYTES;
    fields[5][3] = data;
    ByteBuffer bytes = ByteBuffer.allocate(data + pixels).order(ByteOrder.LITTLE_ENDIAN);
    bytes.putShort((short) 0x4949).putShort((short) 42).putInt(8).putShort((short) fields.length);
    for (int[] field : fields)
      bytes.putShort((short) field[0]).putShort((short) field[1]).putInt(field[2]).putInt(field[3]);
    bytes.putInt(0);
    for (int i = 0; i < edge * edge; i++) bytes.putShort((short) (i * 181));
    return bytes.array();
  }

  private static String shell(Instrumentation test, String command) throws Exception {
    try (InputStream out = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
        test.getUiAutomation().executeShellCommand(command))) {
      return new String(out.readAllBytes(), StandardCharsets.UTF_8).trim();
    }
  }
}
