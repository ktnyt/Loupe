package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.webkit.CookieManager;
import java.io.File;
import org.json.JSONObject;

/**
 * Read-only device test using the explicitly approved OM-1 sample. Never uploads or deletes a
 * source.
 */
public final class ArchiveInstrumentation extends Instrumentation {
  private int tagWidth;
  private boolean sortDimensionsSpecified;
  private float tagFont;
  @Override public void callActivityOnCreate(android.app.Activity activity, Bundle state) {
    if ((tagDetailTest || confidenceUiTest || resultTest || galleryScreenTest || sortTest) && (activity instanceof TagActivity || activity instanceof MainActivity) && tagWidth > 0) {
      android.content.res.Configuration config = new android.content.res.Configuration(activity.getBaseContext().getResources().getConfiguration());
      config.fontScale = tagFont; config.screenWidthDp = tagWidth;
      // Test process resources only; never writes system settings.
      activity.getResources().updateConfiguration(config, activity.getResources().getDisplayMetrics());
    }
    if((confidenceUiTest||tagDetailTest)&&activity instanceof TagActivity)TagFixtureIsolation.install((TagActivity)activity);
    super.callActivityOnCreate(activity, state);
    if ((tagDetailTest || confidenceUiTest || resultTest || galleryScreenTest || sortTest) && tagWidth > 0) activity.getWindow().setLayout(GalleryUi.dp(activity,tagWidth),android.view.ViewGroup.LayoutParams.MATCH_PARENT);
  }
  private boolean galleryScreenTest, galleryTalkBack, viewerScreenTest, shareScreenTest;
  private boolean consistencyTest, syncControlsTest, tagDetailTest, resultTest, resultTalkBack;
  private boolean dashboardTalkBackTest;
  private boolean dashboardLayoutTest;
  private boolean dashboardTest;
  private boolean talkBackStatusTest;
  private boolean visualStatusTest;
  private boolean jobStateTest;
  private boolean affordancesTest;
  private boolean rankingModesTest;
  private boolean systemBackTest;
  private boolean burstTest, realBurstTest;
  private boolean diagnosticTest, sheetTest;
  private boolean tagTest, lifecycleTest;
  private boolean relatedTest;
  private boolean positionTest, sortTest, tabsTest, albumTagsTest, comparisonLabelsTest, actionLabelsTest;
  private boolean automaticTest, confidenceStoreTest, confidenceUiTest;
  private boolean cleanupTest;
  private boolean compactTest;
  private boolean jpegTest;
  private boolean jpegCloudTest;
  private boolean jpegCleanupTest;
  private boolean importLatencyTest, importInterruptionTest, importPauseTest, importDeferredTest, indexSignatureTest, indexApplyRaceTest;
  private volatile long importPauses, importResumes;

  @Override public void callActivityOnPause(android.app.Activity activity) {
    if (importLatencyTest && activity instanceof MainActivity) importPauses++;
    super.callActivityOnPause(activity);
  }

  long importPauses() { return importPauses; }
  long importResumes() { return importResumes; }

  @Override public void callActivityOnResume(android.app.Activity activity) {
    super.callActivityOnResume(activity);
    if (importLatencyTest && activity instanceof MainActivity) importResumes++;
  }

  public void onCreate(Bundle args) {
    super.onCreate(args);
    confidenceUiTest=args!=null&&"tag-confidence".equals(args.getString("mode"));
    confidenceStoreTest=args!=null&&"confidence-store".equals(args.getString("mode"));
    indexSignatureTest = args != null && "index-signature".equals(args.getString("mode"));
    indexApplyRaceTest = args != null && "index-apply-race".equals(args.getString("mode"));
    importDeferredTest = args != null && "import-deferred".equals(args.getString("mode"));
    importPauseTest = args != null && "import-pause-interruption".equals(args.getString("mode"));
    importInterruptionTest = importPauseTest || (args != null && "import-interruption".equals(args.getString("mode")));
    importLatencyTest = importInterruptionTest || importDeferredTest || (args != null && "import-latency".equals(args.getString("mode")));
    sortTest = args != null && "sort-naming".equals(args.getString("mode"));
    shareScreenTest=args!=null&&"share-screen".equals(args.getString("mode"));
    viewerScreenTest=args!=null&&"viewer-screen".equals(args.getString("mode"));
    galleryTalkBack=args!=null&&"gallery-screen-talkback".equals(args.getString("mode"));
    galleryScreenTest=shareScreenTest||viewerScreenTest||galleryTalkBack||(args!=null&&"gallery-screen".equals(args.getString("mode")));
    resultTalkBack=args!=null&&"ranking-result-talkback".equals(args.getString("mode"));
    resultTest=resultTalkBack||(args!=null&&"ranking-result".equals(args.getString("mode")));
    tagDetailTest=args!=null&&"tag-detail".equals(args.getString("mode"));
    if((tagDetailTest || confidenceUiTest || resultTest || galleryScreenTest || sortTest) && args.containsKey("width")){tagWidth=Integer.parseInt(args.getString("width"));tagFont=Float.parseFloat(args.getString("font","1.0"));}
    sortDimensionsSpecified=sortTest && args.containsKey("width");
    syncControlsTest=args!=null&&"sync-controls".equals(args.getString("mode"));
    consistencyTest=args!=null&&"consistency-layout".equals(args.getString("mode"));
    dashboardTalkBackTest=args!=null&&"dashboard-talkback".equals(args.getString("mode"));
    dashboardLayoutTest=args!=null&&"dashboard-layout".equals(args.getString("mode"));
    dashboardTest=args!=null&&"dashboard".equals(args.getString("mode"));
    talkBackStatusTest=args!=null&&"talkback-status".equals(args.getString("mode"));
    visualStatusTest=args!=null&&"visual-status".equals(args.getString("mode"));
    jobStateTest = args != null && "job-state".equals(args.getString("mode"));
    affordancesTest = args != null && "affordances".equals(args.getString("mode"));
    rankingModesTest = args != null && "ranking-modes".equals(args.getString("mode"));
    systemBackTest = args != null && "system-back".equals(args.getString("mode"));
    actionLabelsTest = args != null && "action-labels".equals(args.getString("mode"));
    comparisonLabelsTest = args != null && "comparison-labels".equals(args.getString("mode"));
    albumTagsTest = args != null && "album-tags".equals(args.getString("mode"));
    tabsTest = args != null && "library-tabs".equals(args.getString("mode"));
    positionTest = args != null && "gallery-position".equals(args.getString("mode"));
    burstTest = args != null && "burst".equals(args.getString("mode"));
    realBurstTest = args != null && "burst-real".equals(args.getString("mode"));
    diagnosticTest = args != null && "diagnostics".equals(args.getString("mode"));
    lifecycleTest = args != null && "tag-lifecycle".equals(args.getString("mode"));
    tagTest = args != null && "tags".equals(args.getString("mode"));
    relatedTest = args != null && "related".equals(args.getString("mode"));
    automaticTest = args != null && "automatic".equals(args.getString("mode"));
    cleanupTest = args != null && "cleanup".equals(args.getString("mode"));
    jpegTest = args != null && "jpeg".equals(args.getString("mode"));
    jpegCloudTest = args != null && "jpeg-cloud".equals(args.getString("mode"));
    jpegCleanupTest = args != null && "jpeg-cleanup".equals(args.getString("mode"));
    compactTest = args != null && "compact".equals(args.getString("mode"));
    sheetTest = args != null && "sheets".equals(args.getString("mode"));
    start();
  }

  private void validateSortDimensions(Context context){
    if(tagWidth<=0 || !Float.isFinite(tagFont) || tagFont<=0)throw new IllegalArgumentException("sort dimensions must be finite and positive");
    if(GalleryUi.dp(context,tagWidth)>context.getResources().getDisplayMetrics().widthPixels)throw new IllegalArgumentException("sort width exceeds device");
  }

  public void onStart() {
    Bundle results = new Bundle();
    File restored = null;
    try {
      String report=failureReport(new AssertionError(new java.io.IOException("PRIVATE_VALUE")));
      if(report.contains("PRIVATE_VALUE")||!report.contains("java.io.IOException")||!report.contains("ArchiveInstrumentation.java:"))throw new AssertionError();
      Context context = getTargetContext();
      if(confidenceUiTest){results.putString("stream",ConfidenceUiChecks.run(this,tagWidth));finish(-1,results);return;}
      if(confidenceStoreTest){results.putString("stream",ConfidenceStoreChecks.run(context));finish(-1,results);return;}
      if (indexSignatureTest) { results.putString("stream", IndexSignatureChecks.run(context)); finish(-1, results); return; }
      if (indexApplyRaceTest) { results.putString("stream", IndexApplyRaceChecks.run(this)); finish(-1, results); return; }
      if (importLatencyTest) { results.putString("stream", ImportLatencyChecks.run(this, importInterruptionTest, importPauseTest, importDeferredTest)); finish(-1, results); return; }
      if(sortDimensionsSpecified)validateSortDimensions(context);
      if(shareScreenTest){results.putString("stream",ShareScreenChecks.run(this,tagWidth));finish(-1,results);return;}
      if(viewerScreenTest){results.putString("stream",ViewerScreenChecks.run(this,tagWidth));finish(-1,results);return;}
      if(galleryScreenTest){results.putString("stream",GalleryScreenChecks.run(this,tagWidth,galleryTalkBack));finish(-1,results);return;}
      if(resultTest){results.putString("stream",RankingResultChecks.run(this,tagWidth,resultTalkBack));finish(-1,results);return;}
      if(tagDetailTest){results.putString("stream",TagDetailChecks.run(this,tagWidth));finish(-1,results);return;}
      if(syncControlsTest){results.putString("stream",SyncControlChecks.run(this));finish(-1,results);return;}
      if(consistencyTest){results.putString("stream",ConsistencyLayoutChecks.run(this));finish(-1,results);return;}
      if(dashboardTalkBackTest){results.putString("stream",DashboardTalkBackChecks.run(this));finish(-1,results);return;}
      if(dashboardLayoutTest){results.putString("stream",DashboardLayoutChecks.run(this));finish(-1,results);return;}
      if(dashboardTest){results.putString("stream",DashboardChecks.run(this));finish(-1,results);return;}
      if(talkBackStatusTest){results.putString("stream",TalkBackStatusChecks.run(this));finish(-1,results);return;}
      if(visualStatusTest){results.putString("stream",VisualStatusChecks.run(this));finish(-1,results);return;}
      if (jobStateTest) { results.putString("stream", JobStateChecks.run(context)); finish(-1, results); return; }
      if (affordancesTest) { results.putString("stream", AffordanceChecks.run(this)); finish(-1, results); return; }
      if (rankingModesTest) { results.putString("stream", RankingModeChecks.run(this)); finish(-1, results); return; }
      if (systemBackTest) { results.putString("stream", SystemBackChecks.run(this)); finish(-1, results); return; }
      if (actionLabelsTest) { results.putString("stream", ActionLabelChecks.run(this)); finish(-1, results); return; }
      if (comparisonLabelsTest) { results.putString("stream", ComparisonLabelChecks.run(this)); finish(-1, results); return; }
      if (albumTagsTest) { results.putString("stream", AlbumTagEntryChecks.run(this)); finish(-1, results); return; }
      if (tabsTest) { results.putString("stream", LibraryTabsChecks.run(this)); finish(-1, results); return; }
      if (sortTest) { results.putString("stream", SortNamingChecks.run(this,tagWidth,tagFont)); finish(-1, results); return; }
      if (positionTest) { results.putString("stream", GalleryPositionChecks.run(this)); finish(-1, results); return; }
      if (burstTest || realBurstTest) { results.putString("stream", BurstPreviewChecks.run(this, realBurstTest)); finish(-1, results); return; }
      if (sheetTest) { results.putString("stream", SheetChecks.run(this)); finish(-1, results); return; }
      if (diagnosticTest) {
        results.putString("stream", DiagnosticChecks.run(this)); finish(-1, results); return;
      }
      if (lifecycleTest) {
        results.putString("stream", TagLifecycleChecks.run(this)); finish(-1, results); return;
      }
      if (automaticTest) {
        results.putString("stream", AutomaticTagChecks.run(context) + BatchTagChecks.run(context));
        finish(-1, results);
        return;
      }
      if (relatedTest) {
        results.putString("stream", RelatedPhotoChecks.run(context));
        finish(-1, results);
        return;
      }
      if (tagTest) {
        results.putString("stream", TagStoreChecks.run(context));
        finish(-1, results);
        return;
      }
      if (compactTest) {
        results.putString("stream", testCompact(context));
        finish(-1, results);
        return;
      }
      if (jpegTest) {
        testJpeg(context);
        results.putString(
            "stream",
            "JPEG checks passed: discovery, metadata, bounded preview, original bytes unchanged\n");
        finish(-1, results);
        return;
      }
      if (jpegCloudTest || jpegCleanupTest) {
        testJpegCloud(context);
        results.putString(
            "stream",
            jpegCleanupTest
                ? "JPEG temporary-copy cleanup passed: restored SHA-256, durable preview, original"
                    + " unchanged\n"
                : "JPEG cloud checks passed: restored SHA-256, durable preview, original"
                    + " unchanged\n");
        finish(-1, results);
        return;
      }
      PhotoRepository.Photo sample = null;
      for (PhotoRepository.Photo photo : new PhotoRepository(context).scan("すべて"))
        if (photo.name.equals("P9142895.ORF")
            && photo.cameraGroup().equals("OM-1")
            && "content".equals(photo.uri.getScheme())) {
          sample = photo;
          break;
        }
      if (sample == null) throw new AssertionError("Approved sample missing");
      final String[] cookies = new String[1];
      runOnMainSync(
          () ->
              cookies[0] =
                  CookieManager.getInstance().getCookie(CloudVerifier.ORIGIN + "/drive/v1/nodes"));
      CloudVerifier cloud =
          new CloudVerifier(
              cookies[0],
              context.getSharedPreferences("settings", 0).getString("cloudUserAgent", ""));
      cloud.verify(context, sample, message -> {});
      if (cloud.lastVerified == null) throw new AssertionError("Cloud verification missing");
      if (cleanupTest) {
        testCleanup(context, sample, cookies[0]);
        results.putString(
            "stream",
            "Automatic cleanup device checks passed on a temporary copy; originals unchanged\n");
        finish(-1, results);
        return;
      }
      String key = ArchiveStore.key(sample);
      try (ArchiveStore store = new ArchiveStore(context)) {
        store.verified(sample, cloud.lastVerified);
      }
      try (ArchiveStore store = new ArchiveStore(context)) {
        JSONObject record = store.get(key);
        if (!"verified".equals(record.getString("state")))
          throw new AssertionError("Verification was not durable");
        PhotoRepository.Photo cloudOnly = store.cloudPhoto(record);
        PhotoRepository.Preview preview = new PhotoRepository(context).preview(cloudOnly);
        Bitmap bitmap = PhotoRepository.bitmap(preview, 800);
        if (bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0)
          throw new AssertionError("Archived preview invalid");
        bitmap.recycle();
        restored = new File(context.getCacheDir(), "archive-instrumentation.orf");
        cloud.download(record, restored);
        try (RawPreview.FileSource source = new RawPreview.FileSource(restored)) {
          RawPreview.Result raw = RawPreview.inspect(source);
          if (raw.width < preview.width || raw.height < preview.height)
            throw new AssertionError("Restored preview differs");
        }
        if (!record.getString("sha").equals(CloudVerifier.localSha(context, sample)))
          throw new AssertionError("Source changed");
      }
      results.putString(
          "stream",
          "Archive device checks passed: cloud verification, durable metadata, offline preview,"
              + " original download, source unchanged\n");
      finish(-1, results);
    } catch (Throwable failure) {
      results.putString("stream",failureReport(failure));
      finish(0, results);
    } finally {
      if (restored != null) restored.delete();
    }
  }

  private static String failureReport(Throwable failure){
    return failureLocations(failure)+"Archive device checks failed: "+failure.getClass().getSimpleName()+"\n";
  }
  /** Source locations aid synthetic checks without printing file paths or other exception values. */
  private static String failureLocations(Throwable failure) {
    StringBuilder result=new StringBuilder();
    java.util.Set<Throwable> seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    while(failure!=null&&seen.add(failure)){
      result.append(failure.getClass().getName()).append('\n');
      for(StackTraceElement frame:failure.getStackTrace())result.append("  at ").append(frame).append('\n');
      failure=failure.getCause();
    }
    return result.toString();
  }

  private String testCompact(Context context) throws Exception {
    boolean raw = false, jpeg = false;
    StringBuilder report = new StringBuilder();
    try (ArchiveStore store = new ArchiveStore(context)) {
      for (JSONObject record : store.all()) {
        if (!record.has("sha")) continue;
        PhotoRepository.Photo p = store.cloudPhoto(record);
        if (p.isJpeg() ? jpeg : raw) continue;
        PhotoRepository.Preview proxy = store.preview(record.getString("key"));
        if (!proxy.file.getName().endsWith(".webp")
            || proxy.orientation != 1
            || Math.max(proxy.width, proxy.height) > 1280)
          throw new AssertionError("Compact proxy format or dimensions");
        try (java.io.FileInputStream in = new java.io.FileInputStream(proxy.file)) {
          byte[] header = new byte[12];
          if (in.read(header) != 12
              || !new String(header, 0, 4, java.nio.charset.StandardCharsets.US_ASCII)
                  .equals("RIFF")
              || !new String(header, 8, 4, java.nio.charset.StandardCharsets.US_ASCII)
                  .equals("WEBP")) throw new AssertionError("Proxy is not WebP");
        }
        Bitmap small = PhotoRepository.bitmap(proxy, 400);
        small.recycle();
        PhotoRepository.Photo original = OriginalCache.obtain(context, p);
        if (!record.getString("sha").equals(CloudVerifier.localSha(context, original)))
          throw new AssertionError("Restored original differs");
        PhotoRepository.Preview full = new PhotoRepository(context).fullPreview(original);
        if (Math.max(full.width, full.height) <= Math.max(proxy.width, proxy.height))
          throw new AssertionError("Detail did not use full original");
        PhotoRepository.Photo cached = OriginalCache.obtain(context, p);
        if (!cached.uri.equals(original.uri)) throw new AssertionError("Original cache not reused");
        Bitmap large = PhotoRepository.bitmap(full, 2400);
        large.recycle();
        report
            .append(p.isJpeg() ? "JPEG" : "RAW")
            .append(": proxy=")
            .append(proxy.file.length())
            .append(" bytes, original=")
            .append(p.size)
            .append(" bytes, detail=")
            .append(full.width)
            .append("x")
            .append(full.height)
            .append("\n");
        if (p.isJpeg()) jpeg = true;
        else raw = true;
        if (raw && jpeg) break;
      }
    }
    if (!raw || !jpeg) throw new AssertionError("Verified RAW/JPEG samples missing");
    return "Compact archive and original cache checks passed\n" + report;
  }

  private void testJpegCloud(Context context) throws Exception {
    final String[] cookies = new String[1];
    runOnMainSync(
        () ->
            cookies[0] =
                CookieManager.getInstance().getCookie(CloudVerifier.ORIGIN + "/drive/v1/nodes"));
    CloudVerifier cloud =
        new CloudVerifier(
            cookies[0],
            context.getSharedPreferences("settings", 0).getString("cloudUserAgent", ""));
    int tried = 0;
    for (PhotoRepository.Photo p : new PhotoRepository(context).scan("すべて")) {
      if (!p.isJpeg() || !"content".equals(p.uri.getScheme())) continue;
      if (!p.folder.startsWith("DCIM/Camera/")
          || p.taken <= 0
          || p.taken > System.currentTimeMillis() - 7 * SyncPolicy.DAY) continue;
      cloud.verify(context, p, message -> {});
      if (cloud.lastVerified != null) {
        if (jpegCleanupTest) {
          testCleanup(context, p, cookies[0]);
          return;
        }
        try (ArchiveStore store = new ArchiveStore(context)) {
          store.verified(p, cloud.lastVerified);
          PhotoRepository.Preview preview = store.preview(ArchiveStore.key(p));
          Bitmap bitmap = PhotoRepository.bitmap(preview, 800);
          bitmap.recycle();
          if (!cloud.lastVerified.sha.equals(CloudVerifier.localSha(context, p)))
            throw new AssertionError("Original changed");
        }
        return;
      }
      if (++tried >= 5) break;
    }
    throw new AssertionError("No matching cloud JPEG among samples");
  }

  private void testJpeg(Context context) throws Exception {
    int checked = 0;
    for (PhotoRepository.Photo p : new PhotoRepository(context).scan("すべて")) {
      if (!p.isJpeg() || !"content".equals(p.uri.getScheme())) continue;
      String before = CloudVerifier.localSha(context, p);
      PhotoRepository.Preview preview = new PhotoRepository(context).preview(p);
      if (Math.max(preview.width, preview.height) > 1280 || preview.file.length() == 0)
        throw new AssertionError("JPEG preview bounds");
      try (ArchiveStore store = new ArchiveStore(context)) {
        ArchiveStore.RawMetadata metadata = store.metadata(p);
        if (metadata.model == null) throw new AssertionError("JPEG metadata");
      }
      Bitmap image = PhotoRepository.bitmap(preview, 800);
      if (image == null) throw new AssertionError("JPEG display");
      image.recycle();
      if (!before.equals(CloudVerifier.localSha(context, p)))
        throw new AssertionError("JPEG changed");
      if (++checked == 3) break;
    }
    if (checked != 3) throw new AssertionError("JPEG samples missing");
  }

  private void testCleanup(Context context, PhotoRepository.Photo sample, String cookies)
      throws Exception {
    android.content.SharedPreferences settings = context.getSharedPreferences("settings", 0);
    if (settings.getBoolean("syncEnabled", false) || SyncEngine.RUNNING.get())
      throw new AssertionError("Stop synchronization before test");
    if (!android.os.Environment.isExternalStorageManager())
      throw new AssertionError("Automatic management permission required");
    android.net.Uri copy = null;
    String key = null;
    int previousDays = settings.getInt("retentionDays", 7);
    boolean previousCleanup = settings.getBoolean("cleanupEnabled", false);
    try {
      String name =
          "yohaku-selftest-" + java.util.UUID.randomUUID() + (sample.isJpeg() ? ".jpg" : ".orf");
      android.content.ContentValues values = new android.content.ContentValues();
      values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
      values.put(
          android.provider.MediaStore.MediaColumns.MIME_TYPE,
          sample.isJpeg() ? "image/jpeg" : "image/x-olympus-orf");
      values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
      values.put(
          android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Yohaku-selftest/");
      copy =
          context
              .getContentResolver()
              .insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
      if (copy == null) throw new AssertionError("Cannot create temporary copy");
      try (java.io.InputStream in = context.getContentResolver().openInputStream(sample.uri);
          java.io.OutputStream out = context.getContentResolver().openOutputStream(copy)) {
        if (in == null || out == null) throw new AssertionError("Cannot open temporary copy");
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) != -1) out.write(b, 0, n);
      }
      android.content.ContentValues published = new android.content.ContentValues();
      published.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
      context.getContentResolver().update(copy, published, null, null);
      long modified;
      try (android.database.Cursor cursor =
          context
              .getContentResolver()
              .query(
                  copy,
                  new String[] {
                    android.provider.MediaStore.MediaColumns.DATE_MODIFIED,
                    android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                    android.provider.MediaStore.MediaColumns.SIZE
                  },
                  null,
                  null,
                  null)) {
        if (cursor == null || !cursor.moveToFirst())
          throw new AssertionError("Copy metadata missing");
        modified = cursor.getLong(0);
        name = cursor.getString(1);
        if (cursor.getLong(2) != sample.size)
          throw new AssertionError("Temporary copy size not indexed");
      }
      PhotoRepository.Photo temporary =
          new PhotoRepository.Photo(
              copy,
              name,
              "Pictures/Yohaku-selftest/",
              sample.size,
              modified,
              System.currentTimeMillis() - 8 * SyncPolicy.DAY);
      key = ArchiveStore.key(temporary);
      // Only this generated copy enters the batch, and uploading is disabled for the test.
      settings
          .edit()
          .putBoolean("syncEnabled", true)
          .putBoolean("cleanupEnabled", true)
          .putInt("retentionDays", 7)
          .commit();
      SyncEngine.runBatch(
          context,
          cookies,
          settings.getString("cloudUserAgent", ""),
          java.util.List.of(temporary),
          false);
      settings.edit().putBoolean("syncEnabled", false).putBoolean("cleanupEnabled", false).commit();
      try (ArchiveStore store = new ArchiveStore(context)) {
        JSONObject record = store.get(key);
        if (record == null || !"released".equals(record.getString("state")))
          throw new AssertionError(
              "Temporary copy was not released; metadata unchanged="
                  + SyncEngine.unchanged(context, temporary));
        PhotoRepository.Preview preview =
            new PhotoRepository(context).preview(store.cloudPhoto(record));
        Bitmap image = PhotoRepository.bitmap(preview, 800);
        if (image == null) throw new AssertionError("Preview lost after cleanup");
        image.recycle();
        try (java.io.InputStream original = context.getContentResolver().openInputStream(copy)) {
          if (original != null) throw new AssertionError("Temporary original still exists");
        } catch (java.io.FileNotFoundException expected) {
        }
        PhotoRepository.Photo archived = store.cloudPhoto(record);
        // A fresh UUID gives this archive a new key; restoration must use the cloud.
        PhotoRepository.Photo recovered = OriginalCache.obtain(context, archived);
        if (!record.getString("sha").equals(CloudVerifier.localSha(context, recovered)))
          throw new AssertionError("Restored bytes differ after release");
        PhotoRepository.Preview full = new PhotoRepository(context).fullPreview(recovered);
        Bitmap restoredImage = PhotoRepository.bitmap(full, 1600);
        restoredImage.recycle();
        Bundle stage = new Bundle();
        stage.putString(
            "stream",
            "Deleted local copy, retained WebP "
                + preview.file.length()
                + " bytes, downloaded original "
                + recovered.size
                + " bytes, SHA-256 matched, full preview "
                + full.width
                + "x"
                + full.height
                + "\n");
        sendStatus(0, stage);
        new File(recovered.uri.getPath()).delete();
        if (!record.getString("sha").equals(CloudVerifier.localSha(context, sample)))
          throw new AssertionError("Original changed");
      }
    } finally {
      settings
          .edit()
          .putBoolean("syncEnabled", false)
          .putBoolean("cleanupEnabled", previousCleanup)
          .putInt("retentionDays", previousDays)
          .commit();
      if (copy != null)
        try {
          context.getContentResolver().delete(copy, null, null);
        } catch (Exception ignored) {
        }
      if (key != null) {
        try (ArchiveStore store = new ArchiveStore(context)) {
          store.getWritableDatabase().delete("photos", "key=?", new String[] {key});
        }
        new File(new File(context.getFilesDir(), "archive"), key + ".jpg").delete();
        new File(new File(context.getFilesDir(), "archive"), key + ".webp").delete();
      }
    }
  }
}
