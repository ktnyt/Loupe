package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;

/** Entry point for Loupe instrumentation checks. */
public final class ArchiveInstrumentation extends Instrumentation {
  private boolean systemInsetsTest, singleBestTest, expressiveUiTest, selectedTagsTest;
  private boolean videoTest,
      nanoBananaTest,
      nanoBackgroundTest,
      generationIndicatorTest,
      removeLookTest;
  private boolean material3Test, materialGalleryTest;
  private int tagWidth;
  private boolean sortDimensionsSpecified;
  private float tagFont;

  @Override
  public void callActivityOnCreate(android.app.Activity activity, Bundle state) {
    if ("camera-pilot-wifi-quick".equals(cameraPilotMode))
      CameraWifiQuickChecks.activityCreated(activity);
    if (nanoBackgroundTest) NanoBananaBackgroundChecks.activityCreated(activity);
    boolean dualSlotUi =
        (CameraDualSlotUiChecks.MODE.equals(cameraPilotMode)
                || CameraSdSwapChecks.MODE.equals(cameraPilotMode)
                || CameraSdUsbReprobeChecks.MODE.equals(cameraPilotMode))
            && activity instanceof CameraImportActivity;
    if (amazonRetirementUi
        && (activity instanceof MainActivity || activity instanceof E2SyncActivity)) {
      var config =
          new android.content.res.Configuration(activity.getResources().getConfiguration());
      config.fontScale = tagFont;
      config.screenWidthDp = tagWidth;
      activity
          .getResources()
          .updateConfiguration(config, activity.getResources().getDisplayMetrics());
    }
    if (dualSlotUi) {
      var config =
          new android.content.res.Configuration(activity.getResources().getConfiguration());
      config.fontScale = tagFont;
      config.screenWidthDp = tagWidth;
      activity
          .getResources()
          .updateConfiguration(config, activity.getResources().getDisplayMetrics());
    }
    if ((ep7WifiTest
            || ("camera-pilot-qr-ui".equals(cameraPilotMode)
                || (("camera-pilot-wifi-quick".equals(cameraPilotMode)
                        || "camera-pilot-quick-import".equals(cameraPilotMode))
                    || ("camera-pilot-import-progress".equals(cameraPilotMode)
                        || "camera-pilot-usb-launch".equals(cameraPilotMode)))))
        && (activity instanceof Ep7WifiActivity
            || activity instanceof CameraImportActivity
            || activity instanceof CameraBleActivity)) {
      var config =
          new android.content.res.Configuration(
              activity.getBaseContext().getResources().getConfiguration());
      config.fontScale = tagFont;
      config.screenWidthDp = tagWidth;
      activity
          .getResources()
          .updateConfiguration(config, activity.getResources().getDisplayMetrics());
    }

    if ((systemInsetsTest
            || selectedTagsTest
            || singleBestTest
            || tagDetailTest
            || confidenceUiTest
            || resultTest
            || galleryScreenTest
            || photoEditTest
            || sortTest
            || videoTest
            || removeLookTest)
        && (activity instanceof TagActivity
            || activity instanceof MainActivity
            || activity instanceof VideoActivity
            || removeLookTest && activity instanceof RawEditActivity)
        && tagWidth > 0) {
      android.content.res.Configuration config =
          new android.content.res.Configuration(
              activity.getBaseContext().getResources().getConfiguration());
      config.fontScale = tagFont;
      config.screenWidthDp = tagWidth;
      // Test process resources only; never writes system settings.
      activity
          .getResources()
          .updateConfiguration(config, activity.getResources().getDisplayMetrics());
    }
    if ((confidenceUiTest || tagDetailTest || removeLookTest) && activity instanceof TagActivity)
      TagFixtureIsolation.install((TagActivity) activity);
    if (removeLookTest
        && activity instanceof RawEditActivity
        && state == null
        && activity.getIntent().getBooleanExtra("removeLookLegacyFixture", false))
      state = LookRemovalChecks.legacyState(activity.getIntent());
    boolean photoStorageUi =
        photoStorageUiMode()
            && (activity instanceof PhotoStorageActivity
                || activity instanceof PhotoStorageInventoryActivity);
    if (photoStorageUi) {
      int availablePx = PhotoStorageChecks.availableDisplay(activity).width();
      float density = activity.getResources().getDisplayMetrics().density;
      photoStorageEffectiveWidth = Math.min(tagWidth, (int) Math.floor(availablePx / density));
      var config =
          new android.content.res.Configuration(activity.getResources().getConfiguration());
      config.fontScale = tagFont;
      config.screenWidthDp = photoStorageEffectiveWidth;
      activity
          .getResources()
          .updateConfiguration(config, activity.getResources().getDisplayMetrics());
    }
    super.callActivityOnCreate(activity, state);
    if (dualSlotUi)
      activity
          .getWindow()
          .setLayout(
              GalleryUi.dp(activity, tagWidth), android.view.ViewGroup.LayoutParams.MATCH_PARENT);
    if (amazonRetirementUi
        && (activity instanceof MainActivity || activity instanceof E2SyncActivity)) {
      activity
          .getWindow()
          .setLayout(
              GalleryUi.dp(activity, tagWidth), android.view.ViewGroup.LayoutParams.MATCH_PARENT);
    }
    if (photoStorageUi)
      activity
          .getWindow()
          .setLayout(
              GalleryUi.dp(activity, photoStorageEffectiveWidth),
              android.view.ViewGroup.LayoutParams.MATCH_PARENT);
    if (ep7WifiTest
        || ("camera-pilot-qr-ui".equals(cameraPilotMode)
            || (("camera-pilot-wifi-quick".equals(cameraPilotMode)
                    || "camera-pilot-quick-import".equals(cameraPilotMode))
                || ("camera-pilot-import-progress".equals(cameraPilotMode)
                    || "camera-pilot-usb-launch".equals(cameraPilotMode)))))
      activity
          .getWindow()
          .setLayout(
              GalleryUi.dp(activity, tagWidth), android.view.ViewGroup.LayoutParams.MATCH_PARENT);
    if ((systemInsetsTest
            || selectedTagsTest
            || singleBestTest
            || tagDetailTest
            || confidenceUiTest
            || resultTest
            || galleryScreenTest
            || photoEditTest
            || sortTest
            || videoTest
            || removeLookTest)
        && tagWidth > 0)
      activity
          .getWindow()
          .setLayout(
              GalleryUi.dp(activity, tagWidth), android.view.ViewGroup.LayoutParams.MATCH_PARENT);
  }

  private boolean shareHistoryTest,
      shareHistoryScreenTest,
      shareHistoryHandoffTest,
      shareHistoryCallbackTest;
  private boolean shareHistoryRestartSeed, shareHistoryRestartVerify;
  private boolean cameraTabsTest, ep7WifiTest, cameraLedgerTest, cameraBenchmarkTest;
  private String photoStorageMode;
  private int photoStorageEffectiveWidth;

  int photoStorageEffectiveWidth() {
    return photoStorageEffectiveWidth;
  }

  private boolean photoStorageUiMode() {
    return "photo-storage-ui".equals(photoStorageMode)
        || "photo-storage-ui-run".equals(photoStorageMode)
        || "photo-storage-ui-run-skipped".equals(photoStorageMode)
        || "photo-storage-inventory-ui".equals(photoStorageMode)
        || "photo-storage-archive-ui".equals(photoStorageMode);
  }

  private String cameraPilotMode;
  private boolean shareCapacityTest,
      shareToggleTest,
      galleryScreenTest,
      galleryTalkBack,
      viewerScreenTest,
      shareScreenTest;
  private boolean photoEditTest;
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
  private boolean burstTest, realBurstTest, burstGapTest;
  private boolean diagnosticTest, diagnosticFailureTest, sheetTest;
  private boolean tagTest, lifecycleTest;
  private boolean relatedTest;
  private boolean bulkWinnerTest,
      coWinnerTest,
      positionTest,
      sortTest,
      tabsTest,
      albumTagsTest,
      comparisonLabelsTest,
      actionLabelsTest;
  private boolean automaticTest, confidenceStoreTest, confidenceUiTest;
  private int confidenceRebuildDelayMillis;
  private boolean e2ArchiveTest, e2BackgroundTest, e2CatalogLocalTest, e2CatalogScaleTest;
  private String e2MixedMediaOperation, e2MixedMediaToken, e2LargeOriginalToken;
  private boolean e2LargeOriginalTest, e2LargeCacheTest;
  private boolean e2CleanupBoundaryTest, e2BatchTest;
  private String e2SharedCleanupMode, e2ProductCleanupMode, e2ProductCleanupToken;
  private long e2StaleGeneration, e2StaleId, e2MatchId;
  private String e2CatalogMode;
  private String e2RunId;
  private boolean jpegTest;
  private String amazonRetirementUpgrade;
  private boolean amazonRetirementUi;
  private boolean importLatencyTest,
      importInterruptionTest,
      importPauseTest,
      importDeferredTest,
      indexSignatureTest,
      indexApplyRaceTest,
      archiveScanTest;
  private volatile long importPauses, importResumes;

  @Override
  public void callActivityOnPause(android.app.Activity activity) {
    if (importLatencyTest && activity instanceof MainActivity) importPauses++;
    super.callActivityOnPause(activity);
  }

  long importPauses() {
    return importPauses;
  }

  long importResumes() {
    return importResumes;
  }

  @Override
  public void callActivityOnResume(android.app.Activity activity) {
    super.callActivityOnResume(activity);
    if (importLatencyTest && activity instanceof MainActivity) importResumes++;
  }

  private Bundle cameraPilotArguments;

  public void onCreate(Bundle args) {
    super.onCreate(args);
    cameraPilotArguments = args == null ? new Bundle() : new Bundle(args);
    if (args != null) {
      String mode = args.getString("mode", "");
      if (mode.equals("amazon-retirement-seed")
          || mode.equals("amazon-retirement-verify")
          || mode.equals("amazon-retirement-viewer")
          || mode.equals("amazon-retirement-controls")
          || mode.equals("amazon-retirement-consent-write")
          || mode.equals("amazon-retirement-consent-read")) {
        amazonRetirementUpgrade = mode;
        amazonRetirementUi =
            mode.equals("amazon-retirement-viewer") || mode.equals("amazon-retirement-controls");
      }
    }
    videoTest = args != null && "video".equals(args.getString("mode"));
    nanoBananaTest = args != null && "nano-banana".equals(args.getString("mode"));
    removeLookTest = args != null && "look-removal".equals(args.getString("mode"));
    generationIndicatorTest = args != null && "generation-indicator".equals(args.getString("mode"));
    nanoBackgroundTest = args != null && "nano-background".equals(args.getString("mode"));
    material3Test = args != null && "material3".equals(args.getString("mode"));
    materialGalleryTest = args != null && "gallery-material3".equals(args.getString("mode"));
    shareHistoryTest = args != null && "share-history".equals(args.getString("mode"));
    shareHistoryScreenTest = args != null && "share-history-screen".equals(args.getString("mode"));
    shareHistoryHandoffTest =
        args != null && "share-history-handoff".equals(args.getString("mode"));
    shareHistoryCallbackTest =
        args != null && "share-history-callback".equals(args.getString("mode"));
    shareHistoryRestartSeed =
        args != null && "share-history-restart-seed".equals(args.getString("mode"));
    shareHistoryRestartVerify =
        args != null && "share-history-restart-verify".equals(args.getString("mode"));
    if (shareHistoryScreenTest) {
      tagWidth = Integer.parseInt(args.getString("width", "400"));
      tagFont = Float.parseFloat(args.getString("font", "1.0"));
    }
    confidenceUiTest = args != null && "tag-confidence".equals(args.getString("mode"));
    if (confidenceUiTest) {
      confidenceRebuildDelayMillis =
          switch (args.getString("confidence_rebuild_delay_ms", "0")) {
            case "0" -> 0;
            case "12000" -> 12_000;
            default -> -1;
          };
    }
    confidenceStoreTest = args != null && "confidence-store".equals(args.getString("mode"));
    archiveScanTest = args != null && "archive-scan".equals(args.getString("mode"));
    indexSignatureTest = args != null && "index-signature".equals(args.getString("mode"));
    indexApplyRaceTest = args != null && "index-apply-race".equals(args.getString("mode"));
    importDeferredTest = args != null && "import-deferred".equals(args.getString("mode"));
    importPauseTest = args != null && "import-pause-interruption".equals(args.getString("mode"));
    importInterruptionTest =
        importPauseTest || (args != null && "import-interruption".equals(args.getString("mode")));
    importLatencyTest =
        importInterruptionTest
            || importDeferredTest
            || (args != null && "import-latency".equals(args.getString("mode")));
    coWinnerTest = args != null && "burst-co-winners".equals(args.getString("mode"));
    bulkWinnerTest = args != null && "burst-bulk-winners".equals(args.getString("mode"));
    burstGapTest = args != null && "burst-gap-millis".equals(args.getString("mode"));
    sortTest =
        bulkWinnerTest
            || coWinnerTest
            || (args != null && "sort-naming".equals(args.getString("mode")));
    shareScreenTest = args != null && "share-screen".equals(args.getString("mode"));
    shareCapacityTest = args != null && "share-capacity".equals(args.getString("mode"));
    systemInsetsTest = args != null && "system-insets".equals(args.getString("mode"));
    singleBestTest = args != null && "single-best".equals(args.getString("mode"));
    expressiveUiTest = args != null && "expressive-ui".equals(args.getString("mode"));
    selectedTagsTest = args != null && "selected-tags".equals(args.getString("mode"));
    viewerScreenTest = args != null && "viewer-screen".equals(args.getString("mode"));
    photoEditTest = args != null && "photo-edit".equals(args.getString("mode"));
    shareToggleTest = args != null && "share-toggle".equals(args.getString("mode"));
    galleryTalkBack = args != null && "gallery-screen-talkback".equals(args.getString("mode"));
    galleryScreenTest =
        expressiveUiTest
            || materialGalleryTest
            || shareCapacityTest
            || shareToggleTest
            || shareScreenTest
            || viewerScreenTest
            || galleryTalkBack
            || (args != null && "gallery-screen".equals(args.getString("mode")));
    resultTalkBack = args != null && "ranking-result-talkback".equals(args.getString("mode"));
    resultTest =
        resultTalkBack || (args != null && "ranking-result".equals(args.getString("mode")));
    tagDetailTest = args != null && "tag-detail".equals(args.getString("mode"));
    if ((systemInsetsTest
            || selectedTagsTest
            || singleBestTest
            || tagDetailTest
            || confidenceUiTest
            || resultTest
            || galleryScreenTest
            || photoEditTest
            || sortTest
            || videoTest
            || nanoBananaTest
            || nanoBackgroundTest
            || generationIndicatorTest
            || removeLookTest)
        && args.containsKey("width")) {
      tagWidth = Integer.parseInt(args.getString("width"));
      tagFont = Float.parseFloat(args.getString("font", "1.0"));
    }
    sortDimensionsSpecified = sortTest && args.containsKey("width");
    syncControlsTest = args != null && "sync-controls".equals(args.getString("mode"));
    consistencyTest = args != null && "consistency-layout".equals(args.getString("mode"));
    dashboardTalkBackTest = args != null && "dashboard-talkback".equals(args.getString("mode"));
    dashboardLayoutTest = args != null && "dashboard-layout".equals(args.getString("mode"));
    dashboardTest = args != null && "dashboard".equals(args.getString("mode"));
    talkBackStatusTest = args != null && "talkback-status".equals(args.getString("mode"));
    visualStatusTest = args != null && "visual-status".equals(args.getString("mode"));
    jobStateTest = args != null && "job-state".equals(args.getString("mode"));
    affordancesTest = args != null && "affordances".equals(args.getString("mode"));
    rankingModesTest = args != null && "ranking-modes".equals(args.getString("mode"));
    systemBackTest = args != null && "system-back".equals(args.getString("mode"));
    actionLabelsTest = args != null && "action-labels".equals(args.getString("mode"));
    comparisonLabelsTest = args != null && "comparison-labels".equals(args.getString("mode"));
    albumTagsTest = args != null && "album-tags".equals(args.getString("mode"));
    cameraPilotMode = args == null ? null : args.getString("mode");
    if (cameraPilotMode != null && !cameraPilotMode.startsWith("camera-pilot-"))
      cameraPilotMode = null;
    if (CameraDualSlotUiChecks.MODE.equals(cameraPilotMode)
        || CameraSdSwapChecks.MODE.equals(cameraPilotMode)
        || CameraSdUsbReprobeChecks.MODE.equals(cameraPilotMode)) {
      tagWidth = Integer.parseInt(args.getString("width", "400"));
      tagFont = Float.parseFloat(args.getString("font", "1.0"));
    }
    if (("camera-pilot-qr-ui".equals(cameraPilotMode)
        || (("camera-pilot-wifi-quick".equals(cameraPilotMode)
                || "camera-pilot-quick-import".equals(cameraPilotMode))
            || ("camera-pilot-import-progress".equals(cameraPilotMode)
                || "camera-pilot-usb-launch".equals(cameraPilotMode))))) {
      tagWidth = Integer.parseInt(args.getString("width", "400"));
      tagFont = Float.parseFloat(args.getString("font", "1.0"));
    }
    cameraBenchmarkTest = args != null && "camera-benchmark".equals(args.getString("mode"));
    cameraLedgerTest = args != null && "camera-ledger".equals(args.getString("mode"));
    if (args != null
        && java.util.List.of(
                "photo-storage",
                "photo-storage-import-guard",
                "photo-storage-archive",
                "photo-storage-archive-snapshot",
                "photo-storage-archive-ui",
                "photo-storage-inventory",
                "photo-storage-inventory-ui",
                "photo-storage-performance",
                "photo-storage-ui",
                "photo-storage-ui-run",
                "photo-storage-ui-run-skipped",
                "photo-storage-detach-seed",
                "photo-storage-detach-absent",
                "photo-storage-detach-restored",
                "photo-storage-saf-seed",
                "photo-storage-saf-verify",
                "photo-storage-abandon-detach-seed",
                "photo-storage-abandon-detach-absent",
                "photo-storage-abandon-detach-restored")
            .contains(args.getString("mode", ""))) photoStorageMode = args.getString("mode");
    if (photoStorageUiMode()) {
      tagWidth = Integer.parseInt(args.getString("width", "400"));
      tagFont = Float.parseFloat(args.getString("font", "1.0"));
    }
    ep7WifiTest = args != null && "ep7-wifi".equals(args.getString("mode"));
    if (ep7WifiTest) {
      tagWidth = Integer.parseInt(args.getString("width", "400"));
      tagFont = Float.parseFloat(args.getString("font", "1.0"));
    }
    cameraTabsTest = args != null && "camera-tabs".equals(args.getString("mode"));
    tabsTest = args != null && "library-tabs".equals(args.getString("mode"));
    positionTest = args != null && "gallery-position".equals(args.getString("mode"));
    burstTest = args != null && "burst".equals(args.getString("mode"));
    realBurstTest = args != null && "burst-real".equals(args.getString("mode"));
    diagnosticTest = args != null && "diagnostics".equals(args.getString("mode"));
    diagnosticFailureTest = args != null && "diagnostic-failures".equals(args.getString("mode"));
    lifecycleTest = args != null && "tag-lifecycle".equals(args.getString("mode"));
    tagTest = args != null && "tags".equals(args.getString("mode"));
    relatedTest = args != null && "related".equals(args.getString("mode"));
    automaticTest = args != null && "automatic".equals(args.getString("mode"));
    jpegTest = args != null && "jpeg".equals(args.getString("mode"));
    e2LargeOriginalTest = args != null && "e2-large-original".equals(args.getString("mode"));
    e2LargeCacheTest = args != null && "e2-large-cache".equals(args.getString("mode"));
    if (e2LargeOriginalTest) e2LargeOriginalToken = args.getString("fixture");
    e2CatalogLocalTest = args != null && "e2-catalog-local".equals(args.getString("mode"));
    e2CatalogScaleTest = args != null && "e2-catalog-scale".equals(args.getString("mode"));
    if (args != null && args.getString("mode", "").startsWith("e2-mixed-")) {
      e2MixedMediaOperation = args.getString("mode").substring("e2-mixed-".length());
      e2MixedMediaToken = args.getString("fixture");
    }
    e2ArchiveTest = args != null && "e2-archive".equals(args.getString("mode"));
    if (e2ArchiveTest) e2RunId = args.getString("e2-run");
    e2BackgroundTest = args != null && "e2-background".equals(args.getString("mode"));
    e2BatchTest = args != null && "e2-batches".equals(args.getString("mode"));
    e2CleanupBoundaryTest = args != null && "e2-cleanup-boundary".equals(args.getString("mode"));
    if (args != null && args.getString("mode", "").startsWith("e2-cleanup-product-")) {
      e2ProductCleanupMode = args.getString("mode").substring("e2-cleanup-product-".length());
      e2ProductCleanupToken = args.getString("fixture");
    }
    if (args != null && args.getString("mode", "").startsWith("e2-cleanup-shared-")) {
      e2SharedCleanupMode = args.getString("mode");
      e2StaleGeneration = Long.parseLong(args.getString("generation", "0"));
      e2StaleId = Long.parseLong(args.getString("stale-id", "0"));
      e2MatchId = Long.parseLong(args.getString("match-id", "0"));
    }
    if (args != null
        && java.util.Set.of(
                "e2-catalog-publish",
                "e2-catalog-republish",
                "e2-catalog-restore",
                "e2-catalog-previous")
            .contains(args.getString("mode", ""))) e2CatalogMode = args.getString("mode");
    sheetTest = args != null && "sheets".equals(args.getString("mode"));
    if (amazonRetirementUi) {
      tagWidth = Integer.parseInt(args.getString("width", "400"));
      tagFont = Float.parseFloat(args.getString("font", "1.0"));
    }
    start();
  }

  private void validateSortDimensions(Context context) {
    if (tagWidth <= 0 || !Float.isFinite(tagFont) || tagFont <= 0)
      throw new IllegalArgumentException("sort dimensions must be finite and positive");
    if (GalleryUi.dp(context, tagWidth) > context.getResources().getDisplayMetrics().widthPixels)
      throw new IllegalArgumentException("sort width exceeds device");
  }

  public void onStart() {
    Bundle results = new Bundle();
    try {
      String report = failureReport(new AssertionError(new java.io.IOException("PRIVATE_VALUE")));
      if (report.contains("PRIVATE_VALUE")
          || !report.contains("java.io.IOException")
          || !report.contains("ArchiveInstrumentation.java:")) throw new AssertionError();
      Context context = getTargetContext();
      String mode = cameraPilotArguments.getString("mode", "");
      if ("ranking-upgrade-seed".equals(mode)) {
        results.putString("stream", RankingUpgradeSeedChecks.run(this, cameraPilotArguments));
        finish(-1, results);
        return;
      }
      if ("ranking-upgrade-verify".equals(mode)) {
        results.putString("stream", RankingUpgradeChecks.run(this, cameraPilotArguments));
        finish(-1, results);
        return;
      }
      if ("ranking-identity-snapshot".equals(mode)) {
        results.putString("stream", RankingIdentitySnapshotChecks.run(this));
        finish(-1, results);
        return;
      }
      if ("ranking-persistence-waits".equals(mode)) {
        results.putString("stream", RankingPersistenceWaitChecks.run(this, cameraPilotArguments));
        finish(-1, results);
        return;
      }
      if ("release-upgrade-seed".equals(mode) || "release-upgrade-verify".equals(mode)) {
        results.putString(
            "stream", ReleaseUpgradeChecks.run(context, "release-upgrade-seed".equals(mode)));
        finish(-1, results);
        return;
      }
      if ("camera-history-migration".equals(mode)) {
        results.putString("stream", CameraSyncHistoryMigrationCheck.run(context));
        finish(-1, results);
        return;
      }
      if (selectedTagsTest) {
        results.putString(
            "stream",
            SelectedPhotoTagsChecks.run(
                this, tagWidth > 0 ? tagWidth : 400, tagFont > 0 ? tagFont : 1f));
        finish(-1, results);
        return;
      }
      if (expressiveUiTest) {
        results.putString(
            "stream",
            ExpressiveUiChecks.run(
                this, tagWidth > 0 ? tagWidth : 400, tagFont > 0 ? tagFont : 1f));
        finish(-1, results);
        return;
      }
      if (systemInsetsTest) {
        results.putString(
            "stream",
            WindowInsetsChecks.run(
                this, tagWidth > 0 ? tagWidth : 400, tagFont > 0 ? tagFont : 1f));
        finish(-1, results);
        return;
      }
      if (singleBestTest) {
        results.putString(
            "stream",
            SingleBestChecks.run(this, tagWidth > 0 ? tagWidth : 400, tagFont > 0 ? tagFont : 1f));
        finish(-1, results);
        return;
      }
      if (removeLookTest) {
        results.putString(
            "stream",
            LookRemovalChecks.run(this, tagWidth > 0 ? tagWidth : 400, tagFont > 0 ? tagFont : 1f));
        finish(-1, results);
        return;
      }
      if (generationIndicatorTest) {
        results.putString(
            "stream",
            GenerationIndicatorChecks.run(
                this, tagWidth > 0 ? tagWidth : 400, tagFont > 0 ? tagFont : 1f));
        finish(-1, results);
        return;
      }
      if (nanoBackgroundTest) {
        results.putString(
            "stream",
            NanoBananaBackgroundChecks.run(
                this, tagWidth > 0 ? tagWidth : 400, tagFont > 0 ? tagFont : 1f));
        finish(-1, results);
        return;
      }
      if (nanoBananaTest) {
        NanoBananaApiChecks.run();
        results.putString(
            "stream",
            NanoBananaChecks.run(this, tagWidth > 0 ? tagWidth : 400, tagFont > 0 ? tagFont : 1f)
                + "Synthetic HTTPS API outcomes, cause retention, no POST retry and disconnect"
                + " covered.\n");
        finish(-1, results);
        return;
      }
      if (videoTest) {
        results.putString(
            "stream",
            VideoChecks.run(this, tagWidth > 0 ? tagWidth : 400, tagFont > 0 ? tagFont : 1f));
        finish(-1, results);
        return;
      }
      if (e2LargeCacheTest) {
        results.putString("stream", E2LargeOriginalChecks.cache(this));
        finish(-1, results);
        return;
      }
      if (e2LargeOriginalTest) {
        results.putString("stream", E2LargeOriginalChecks.run(this, e2LargeOriginalToken));
        finish(-1, results);
        return;
      }
      if (e2CatalogScaleTest) {
        results.putString("stream", E2CatalogChecks.runScale(context));
        finish(-1, results);
        return;
      }
      if (e2CatalogLocalTest) {
        results.putString("stream", E2CatalogChecks.run(context));
        finish(-1, results);
        return;
      }
      if (e2MixedMediaOperation != null) {
        results.putString(
            "stream", E2MixedMediaChecks.run(this, e2MixedMediaOperation, e2MixedMediaToken));
        finish(-1, results);
        return;
      }
      if ("e2-transfer-dashboard".equals(cameraPilotArguments.getString("mode"))) {
        results.putString("stream", E2TransferDashboardChecks.run(this));
        finish(-1, results);
        return;
      }
      if (e2BatchTest) {
        results.putString("stream", E2BatchChecks.run(context));
        finish(-1, results);
        return;
      }
      if (e2CleanupBoundaryTest) {
        results.putString("stream", E2BackgroundChecks.cleanupBoundary(this));
        finish(-1, results);
        return;
      }
      if (e2ProductCleanupMode != null) {
        results.putString(
            "stream",
            E2BackgroundChecks.product(this, e2ProductCleanupMode, e2ProductCleanupToken));
        finish(-1, results);
        return;
      }
      if (e2SharedCleanupMode != null) {
        results.putString(
            "stream",
            E2BackgroundChecks.sharedCleanup(
                this, e2SharedCleanupMode, e2StaleGeneration, e2StaleId, e2MatchId));
        finish(-1, results);
        return;
      }
      if (e2CatalogMode != null) {
        results.putString("stream", E2BackgroundChecks.catalog(this, e2CatalogMode));
        finish(-1, results);
        return;
      }
      if (e2BackgroundTest) {
        results.putString("stream", E2BackgroundChecks.run(this));
        finish(-1, results);
        return;
      }
      if (e2ArchiveTest) {
        results.putString("stream", E2DeviceChecks.run(context, e2RunId));
        finish(-1, results);
        return;
      }
      if (confidenceUiTest) {
        if (confidenceRebuildDelayMillis < 0) {
          throw new IllegalArgumentException("confidence_rebuild_delay_ms must be 0 or 12000");
        }
        results.putString("stream", ConfidenceUiChecks.run(this, tagWidth));
        finish(-1, results);
        return;
      }
      if (confidenceStoreTest) {
        results.putString("stream", ConfidenceStoreChecks.run(context));
        finish(-1, results);
        return;
      }
      if (amazonRetirementUpgrade != null) {
        AmazonRetirementUpgradeChecks.requireDisposable(
            context, "true".equals(cameraPilotArguments.getString("disposable-avd")));
        results.putString(
            "stream",
            switch (amazonRetirementUpgrade) {
              case "amazon-retirement-seed" ->
                  AmazonRetirementUpgradeChecks.seed(
                      this, cameraPilotArguments.getString("consent", ""));
              case "amazon-retirement-verify" -> AmazonRetirementUpgradeChecks.verify(this);
              case "amazon-retirement-viewer" ->
                  AmazonRetirementUpgradeChecks.viewer(this, tagWidth, tagFont);
              case "amazon-retirement-consent-write" ->
                  AmazonRetirementUpgradeChecks.writeConsentRestart(
                      this, cameraPilotArguments.getString("consent-run", ""));
              case "amazon-retirement-consent-read" ->
                  AmazonRetirementUpgradeChecks.readConsentRestart(
                      this, cameraPilotArguments.getString("consent-run", ""));
              default -> AmazonRetirementUpgradeChecks.controls(this, tagWidth, tagFont);
            });
        finish(-1, results);
        return;
      }
      if (archiveScanTest) {
        results.putString("stream", ArchiveScanChecks.run(context));
        finish(-1, results);
        return;
      }
      if (indexSignatureTest) {
        results.putString("stream", IndexSignatureChecks.run(context));
        finish(-1, results);
        return;
      }
      if (indexApplyRaceTest) {
        results.putString("stream", IndexApplyRaceChecks.run(this));
        finish(-1, results);
        return;
      }
      if (shareHistoryRestartSeed || shareHistoryRestartVerify) {
        results.putString(
            "stream",
            shareHistoryRestartSeed
                ? ShareHistoryRestartChecks.seed(context)
                : ShareHistoryRestartChecks.verify(context));
        finish(-1, results);
        return;
      }
      if (shareHistoryScreenTest) {
        results.putString("stream", ShareHistoryScreenChecks.run(this, tagWidth, tagFont));
        finish(-1, results);
        return;
      }
      if (shareHistoryHandoffTest) {
        results.putString("stream", ShareHistoryHandoffChecks.run(this));
        finish(-1, results);
        return;
      }
      if (shareHistoryCallbackTest) {
        results.putString("stream", ShareHistoryCallbackChecks.run(this));
        finish(-1, results);
        return;
      }
      if (shareHistoryTest) {
        String storeResult = ShareHistoryChecks.run(context);
        runOnMainSync(() -> ShareHistoryUiChecks.run(context));
        results.putString(
            "stream",
            storeResult
                + "PASS share-history UI: narrow tiles, font scaling, AX, noninteractive badges\n");
        finish(-1, results);
        return;
      }
      if (importLatencyTest) {
        results.putString(
            "stream",
            ImportLatencyChecks.run(
                this, importInterruptionTest, importPauseTest, importDeferredTest));
        finish(-1, results);
        return;
      }
      if (sortDimensionsSpecified) validateSortDimensions(context);
      if ("viewer-load".equals(cameraPilotArguments.getString("mode"))) {
        results.putString("stream", ViewerPhotoLoadChecks.run(this));
        finish(-1, results);
        return;
      }
      if (shareScreenTest) {
        results.putString("stream", ShareScreenChecks.run(this, tagWidth));
        finish(-1, results);
        return;
      }
      if (shareCapacityTest) {
        results.putString("stream", ShareCapacityUiChecks.run(this, tagWidth, tagFont));
        finish(-1, results);
        return;
      }
      if (photoEditTest) {
        results.putString("stream", PhotoEditChecks.run(this, tagWidth, tagFont));
        finish(-1, results);
        return;
      }
      if (viewerScreenTest) {
        results.putString("stream", ViewerScreenChecks.run(this, tagWidth));
        finish(-1, results);
        return;
      }
      if (galleryScreenTest) {
        results.putString(
            "stream",
            GalleryScreenChecks.run(
                this, tagWidth, galleryTalkBack, shareToggleTest, materialGalleryTest));
        finish(-1, results);
        return;
      }
      if (resultTest) {
        results.putString("stream", RankingResultChecks.run(this, tagWidth, resultTalkBack));
        finish(-1, results);
        return;
      }
      if (tagDetailTest) {
        results.putString("stream", TagDetailChecks.run(this, tagWidth));
        finish(-1, results);
        return;
      }
      if (syncControlsTest) {
        results.putString("stream", SyncControlChecks.run(this));
        finish(-1, results);
        return;
      }
      if (consistencyTest) {
        results.putString("stream", ConsistencyLayoutChecks.run(this));
        finish(-1, results);
        return;
      }
      if (dashboardTalkBackTest) {
        results.putString("stream", DashboardTalkBackChecks.run(this));
        finish(-1, results);
        return;
      }
      if (dashboardLayoutTest) {
        results.putString("stream", DashboardLayoutChecks.run(this));
        finish(-1, results);
        return;
      }
      if (dashboardTest) {
        results.putString("stream", DashboardChecks.run(this));
        finish(-1, results);
        return;
      }
      if (talkBackStatusTest) {
        results.putString("stream", TalkBackStatusChecks.run(this));
        finish(-1, results);
        return;
      }
      if (visualStatusTest) {
        results.putString("stream", VisualStatusChecks.run(this));
        finish(-1, results);
        return;
      }
      if (jobStateTest) {
        results.putString("stream", JobStateChecks.run(context));
        finish(-1, results);
        return;
      }
      if (affordancesTest) {
        results.putString("stream", AffordanceChecks.run(this));
        finish(-1, results);
        return;
      }
      if (rankingModesTest) {
        results.putString("stream", RankingModeChecks.run(this));
        finish(-1, results);
        return;
      }
      if (systemBackTest) {
        results.putString("stream", SystemBackChecks.run(this));
        finish(-1, results);
        return;
      }
      if (actionLabelsTest) {
        results.putString("stream", ActionLabelChecks.run(this));
        finish(-1, results);
        return;
      }
      if (comparisonLabelsTest) {
        results.putString("stream", ComparisonLabelChecks.run(this));
        finish(-1, results);
        return;
      }
      if ((albumTagsTest || tabsTest || positionTest)
          && "true".equals(cameraPilotArguments.getString("libraryFixture"))) {
        String libraryReport;
        try (LibraryMediaFixture fixture = new LibraryMediaFixture(context)) {
          fixture.seed();
          libraryReport =
              albumTagsTest
                  ? AlbumTagEntryChecks.run(this)
                  : tabsTest ? LibraryTabsChecks.run(this) : GalleryPositionChecks.run(this);
          if (libraryReport.contains("SKIP ") || libraryReport.contains("UNVERIFIED ")) {
            throw new AssertionError(
                "Rich library test did not validate every fixture-dependent check");
          }
        }
        results.putString("stream", libraryReport);
        finish(-1, results);
        return;
      }
      if (albumTagsTest) {
        results.putString("stream", AlbumTagEntryChecks.run(this));
        finish(-1, results);
        return;
      }
      if (material3Test) {
        results.putString("stream", Material3Checks.run(this));
        finish(-1, results);
        return;
      }
      if (cameraPilotMode != null) {
        String result;
        if (cameraPilotMode.equals("camera-pilot-wifi")) {
          android.app.Activity pilotUi =
              startActivitySync(
                  new android.content.Intent(getTargetContext(), Ep7WifiActivity.class)
                      .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
          try {
            runOnMainSync(
                () -> {
                  pilotUi
                      .getWindow()
                      .addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                  disablePilotActions(pilotUi.getWindow().getDecorView());
                  android.widget.TextView status =
                      pilotUi.getWindow().getDecorView().findViewWithTag("ep7-status");
                  if (status != null) status.setText("4枚の保存と差分取り込みを検査しています…");
                });
            result = CameraImportPilotChecks.runWifi(this);
          } finally {
            runOnMainSync(
                () -> {
                  pilotUi
                      .getWindow()
                      .clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                  pilotUi.finish();
                });
          }
        } else if (cameraPilotMode.equals("camera-pilot-usb-launch-choice"))
          result = CameraUsbLaunchChecks.choice(this, cameraPilotArguments);
        else if (cameraPilotMode.equals("camera-pilot-usb-launch"))
          result = CameraUsbLaunchChecks.run(this);
        else if (CameraOperationAdmissionChecks.MODE.equals(cameraPilotMode))
          result = CameraOperationAdmissionChecks.run(this);
        else if (CameraDualSlotUiChecks.MODE.equals(cameraPilotMode))
          result = CameraDualSlotUiChecks.run(this, tagWidth, tagFont);
        else if (CameraSdSwapChecks.MODE.equals(cameraPilotMode))
          result = CameraSdSwapChecks.run(this, tagWidth, tagFont);
        else if (CameraSdUsbReprobeChecks.MODE.equals(cameraPilotMode))
          result = CameraSdUsbReprobeChecks.run(this, tagWidth, tagFont);
        else if (cameraPilotMode.equals("camera-pilot-import-progress"))
          result = CameraImportProgressChecks.run(this);
        else if (cameraPilotMode.equals("camera-pilot-import-history"))
          result = CameraImportHistoryChecks.run(this);
        else if (cameraPilotMode.equals("camera-pilot-wifi-quick"))
          result = CameraWifiQuickChecks.run(this);
        else if (cameraPilotMode.equals("camera-pilot-quick-import"))
          result = CameraQuickImportChecks.run(this);
        else if (cameraPilotMode.equals("camera-pilot-qr-ui")) result = CameraQrUiChecks.run(this);
        else if (cameraPilotMode.equals("camera-pilot-qr-image"))
          result = CameraQrImageChecks.run(this);
        else if (cameraPilotMode.equals("camera-pilot-qr-status"))
          result = CameraSavedWakeChecks.status(this);
        else if (cameraPilotMode.equals("camera-pilot-sd-source-diagnostic"))
          result = CameraImportPilotChecks.runSdSourceDiagnostic(this, cameraPilotArguments);
        else if (cameraPilotMode.equals("camera-pilot-sd-source-synthetic"))
          result = CameraImportPilotChecks.runSdSourceDiagnosticSyntheticChecks();
        else if (cameraPilotMode.equals("camera-pilot-sd-original"))
          result = jp.nano.rawgallery.camera.CameraDocumentSourceChecks.run(this);
        else if (cameraPilotMode.equals("camera-pilot-sd-revision"))
          result = jp.nano.rawgallery.camera.SdSavedRevisionChecks.run(this);
        else if (cameraPilotMode.equals("camera-pilot-paired-guards"))
          result = CameraPairedPilotChecks.guardChecks(this);
        else if (cameraPilotMode.equals("camera-pilot-paired-wifi"))
          result = CameraPairedPilotChecks.run(this);
        else if (cameraPilotMode.equals("camera-pilot-paired-session"))
          result = jp.nano.rawgallery.camera.CameraPairedSessionChecks.run();
        else if (cameraPilotMode.equals("camera-pilot-ble-advertisement"))
          result = CameraSavedWakeChecks.advertisement(this);
        else if (cameraPilotMode.equals("camera-pilot-ble-saved"))
          result = CameraSavedWakeChecks.run(this);
        else if (cameraPilotMode.equals("camera-pilot-qr-credentials"))
          result = jp.nano.rawgallery.camera.CameraCredentialChecks.run(this);
        else if (cameraPilotMode.equals("camera-pilot-qr-seed"))
          result = jp.nano.rawgallery.camera.CameraCredentialChecks.seed(this);
        else if (cameraPilotMode.equals("camera-pilot-qr-verify"))
          result = jp.nano.rawgallery.camera.CameraCredentialChecks.verify(this);
        else if (cameraPilotMode.equals("camera-pilot-ble-diagnostics"))
          result = CameraBleDiagnosticsChecks.run(this);
        else if (cameraPilotMode.equals("camera-pilot-sd-guards"))
          result = CameraImportPilotChecks.runSdGuardChecks(this);
        else if (cameraPilotMode.equals("camera-pilot-sd"))
          result = CameraImportPilotChecks.runSd(this);
        else if (cameraPilotMode.equals("camera-pilot-cleanup"))
          result = CameraImportPilotChecks.cleanup(this);
        else throw new IllegalArgumentException("Unknown pilot mode");
        results.putString("stream", result);
        finish(-1, results);
        return;
      }
      if (cameraBenchmarkTest) {
        results.putString("stream", CameraImportBenchmarkChecks.run(this));
        finish(-1, results);
        return;
      }
      if (cameraLedgerTest) {
        results.putString("stream", CameraImportLedgerChecks.run(this));
        finish(-1, results);
        return;
      }
      if (photoStorageMode != null) {
        String secondary = cameraPilotArguments.getString("secondaryVolume");
        String photoStorageReport;
        if ("photo-storage-import-guard".equals(photoStorageMode)) {
          photoStorageReport = PhotoStorageChecks.checkImportHistoryGuard(this);
        } else if ("photo-storage-archive".equals(photoStorageMode)) {
          photoStorageReport = LegacyArchiveMoveChecks.run(this, secondary);
        } else if ("photo-storage-archive-snapshot".equals(photoStorageMode)) {
          String cadence = cameraPilotArguments.getString("archive_cadence_ms");
          photoStorageReport = LegacyArchiveMoveChecks.checkSnapshots(this, secondary, cadence);
        } else if ("photo-storage-archive-ui".equals(photoStorageMode)) {
          photoStorageReport =
              LegacyArchiveMoveChecks.captureUi(this, secondary, tagWidth, tagFont);
        } else if ("photo-storage-inventory-ui".equals(photoStorageMode)) {
          int records = Integer.parseInt(cameraPilotArguments.getString("history_count", "1900"));
          photoStorageReport =
              PhotoStorageChecks.captureInventoryUi(this, secondary, tagWidth, tagFont, records);
        } else if (photoStorageUiMode()) {
          photoStorageReport =
              PhotoStorageChecks.captureUi(
                  this,
                  secondary,
                  tagWidth,
                  tagFont,
                  !"photo-storage-ui".equals(photoStorageMode),
                  "photo-storage-ui-run".equals(photoStorageMode) ? 0 : 1);
        } else if ("photo-storage-performance".equals(photoStorageMode)) {
          photoStorageReport =
              PhotoStorageChecks.performance(this, secondary, cameraPilotArguments);
        } else {
          photoStorageReport = PhotoStorageChecks.run(this, photoStorageMode, secondary);
        }
        results.putString("stream", photoStorageReport);
        finish(-1, results);
        return;
      }
      if (ep7WifiTest) {
        results.putString("stream", Ep7WifiChecks.run(this));
        finish(-1, results);
        return;
      }
      if (cameraTabsTest) {
        results.putString("stream", CameraTabsChecks.run(this));
        finish(-1, results);
        return;
      }
      if (tabsTest) {
        results.putString("stream", LibraryTabsChecks.run(this));
        finish(-1, results);
        return;
      }
      if (burstGapTest) {
        results.putString(
            "stream",
            BurstGapChecks.run(
                this,
                Boolean.parseBoolean(cameraPilotArguments.getString("numbered")),
                cameraPilotArguments.getString("raw_samples")));
        finish(-1, results);
        return;
      }
      if (bulkWinnerTest) {
        results.putString("stream", BurstBulkWinnerChecks.run(this, tagWidth, tagFont));
        finish(-1, results);
        return;
      }
      if (coWinnerTest) {
        results.putString("stream", BurstCoWinnerChecks.run(this, tagWidth, tagFont));
        finish(-1, results);
        return;
      }
      if (sortTest) {
        results.putString("stream", SortNamingChecks.run(this, tagWidth, tagFont));
        finish(-1, results);
        return;
      }
      if (positionTest) {
        results.putString("stream", GalleryPositionChecks.run(this));
        finish(-1, results);
        return;
      }
      if (burstTest || realBurstTest) {
        results.putString("stream", BurstPreviewChecks.run(this, realBurstTest));
        finish(-1, results);
        return;
      }
      if (sheetTest) {
        results.putString("stream", SheetChecks.run(this));
        finish(-1, results);
        return;
      }
      if ("gallery-photo-keys".equals(cameraPilotArguments.getString("mode"))) {
        results.putString("stream", GalleryPhotoKeyChecks.run(this, cameraPilotArguments));
        finish(-1, results);
        return;
      }
      if ("gallery-photo-key-compat".equals(mode)) {
        results.putString("stream", GalleryPhotoKeyCompatibilityChecks.run(this));
        finish(-1, results);
        return;
      }
      if ("diagnostic-local-export".equals(cameraPilotArguments.getString("mode"))) {
        results.putString("stream", DiagnosticExportChecks.run(context));
        finish(-1, results);
        return;
      }
      if (diagnosticTest) {
        results.putString("stream", DiagnosticChecks.run(this));
        finish(-1, results);
        return;
      }
      if (diagnosticFailureTest) {
        results.putString("stream", DiagnosticFailureChecks.run(this));
        finish(-1, results);
        return;
      }
      if ("media-observer-lifecycle".equals(cameraPilotArguments.getString("mode"))) {
        results.putString("stream", MediaObserverLifecycleChecks.run(this));
        finish(-1, results);
        return;
      }
      if (lifecycleTest) {
        results.putString("stream", TagLifecycleChecks.run(this));
        finish(-1, results);
        return;
      }
      if (automaticTest) {
        results.putString(
            "stream", "PASS " + AutomaticTagChecks.run(context) + BatchTagChecks.run(context));
        finish(-1, results);
        return;
      }
      if (relatedTest) {
        results.putString("stream", RelatedPhotoChecks.run(context));
        finish(-1, results);
        return;
      }
      if (tagTest) {
        results.putString("stream", "PASS " + TagStoreChecks.run(context));
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
      throw new IllegalArgumentException("Specify a supported instrumentation mode");
    } catch (Throwable failure) {
      results.putString("stream", failureReport(failure));
      finish(0, results);
    }
  }

  int confidenceRebuildDelayMillis() {
    return confidenceRebuildDelayMillis;
  }

  private static String failureReport(Throwable failure) {
    return failureLocations(failure)
        + "Archive device checks failed: "
        + failure.getClass().getSimpleName()
        + "\n";
  }

  /**
   * Source locations aid synthetic checks without printing file paths or other exception values.
   */
  private static String failureLocations(Throwable failure) {
    StringBuilder result = new StringBuilder();
    java.util.Set<Throwable> seen =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    appendFailureLocations(result, seen, failure, "");
    return result.toString();
  }

  private static void appendFailureLocations(
      StringBuilder result, java.util.Set<Throwable> seen, Throwable failure, String prefix) {
    if (failure == null || !seen.add(failure)) return;
    result.append(prefix).append(failure.getClass().getName());
    if (failure instanceof PhotoStorageChecks.CheckFailure
        || failure instanceof PhotoEditChecks.MonoTintFailure)
      result.append(": ").append(failure.getMessage());
    result.append('\n');
    for (StackTraceElement frame : failure.getStackTrace())
      result.append("  at ").append(frame).append('\n');
    for (Throwable suppressed : failure.getSuppressed())
      appendFailureLocations(result, seen, suppressed, "Suppressed: ");
    appendFailureLocations(result, seen, failure.getCause(), "Caused by: ");
  }

  private void testJpeg(Context context) throws Exception {
    int checked = 0;
    for (PhotoRepository.Photo p : new PhotoRepository(context).scan("すべて")) {
      if (!p.isJpeg() || !"content".equals(p.uri.getScheme())) continue;
      String before = jpegSha(context, p);
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
      if (!before.equals(jpegSha(context, p))) throw new AssertionError("JPEG changed");
      if (++checked == 3) break;
    }
    if (checked != 3) throw new AssertionError("JPEG samples missing");
  }

  private static String jpegSha(Context context, PhotoRepository.Photo photo) throws Exception {
    var digest = java.security.MessageDigest.getInstance("SHA-256");
    try (var original = PhotoRepository.original(context, photo)) {
      byte[] buffer = new byte[65_536];
      for (int count; (count = original.read(buffer)) >= 0; ) digest.update(buffer, 0, count);
    }
    return java.util.HexFormat.of().formatHex(digest.digest());
  }

  private static void disablePilotActions(android.view.View view) {
    if (view instanceof android.widget.Button) view.setEnabled(false);
    if (view instanceof android.view.ViewGroup)
      for (int i = 0; i < ((android.view.ViewGroup) view).getChildCount(); i++)
        disablePilotActions(((android.view.ViewGroup) view).getChildAt(i));
  }
}
