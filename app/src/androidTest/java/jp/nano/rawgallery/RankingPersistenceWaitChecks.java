package jp.nano.rawgallery;

import static jp.nano.rawgallery.RankingResultChecks.call;
import static jp.nano.rawgallery.RankingResultChecks.get;
import static jp.nano.rawgallery.RankingResultChecks.set;
import static jp.nano.rawgallery.TagDetailChecks.require;
import static jp.nano.rawgallery.TagDetailChecks.ui;

import android.Manifest;
import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Test-only lock and frame proof against real MainActivity controls and isolated XML. */
final class RankingPersistenceWaitChecks {
  private static final String AVD = "loupe249-ranking-baseline";
  private static final long PROOF_MS = 650;
  private static final long GATE_LIMIT_MS = 2200;
  private static final long FRAME_WAIT_MS = 5000;
  private static final long CAPTURE_GATE_MS = 3500;
  private static final List<Integer> BURST = List.of(1, 2, 3);

  private enum Probe {
    NONE,
    ZOOM,
    CATALOG,
    CLEANUP
  }

  static String run(Instrumentation test, Bundle args) throws Exception {
    requireEmptyAvd(test);
    boolean baseline =
        switch (args.getString("baseline", "false")) {
          case "true" -> true;
          case "false" -> false;
          default -> throw new IllegalArgumentException("baseline must be true or false");
        };
    int entries =
        switch (args.getString("ranking_entries", "200")) {
          case "200" -> 200;
          case "2000" -> 2000;
          case "10000" -> 10000;
          default -> throw new IllegalArgumentException("unsupported ranking_entries count");
        };

    boolean capture =
        switch (args.getString("capture_states", "false")) {
          case "true" -> true;
          case "false" -> false;
          default -> throw new IllegalArgumentException("unsupported capture_states");
        };
    Probe probe =
        switch (args.getString("ranking_probe", "none")) {
          case "none" -> Probe.NONE;
          case "zoom" -> Probe.ZOOM;
          case "catalog" -> Probe.CATALOG;
          case "cleanup" -> Probe.CLEANUP;
          default -> throw new IllegalArgumentException("unsupported ranking_probe");
        };
    require(!baseline || !capture, "capture states require the candidate");
    require(probe == Probe.NONE || !baseline, "probe requires the candidate");
    require(probe != Probe.CLEANUP || !capture, "cleanup probe has no UI");
    int captureWidth =
        switch (args.getString("capture_width", "native")) {
          case "native" -> 0;
          case "320" -> 320;
          default -> throw new IllegalArgumentException("unsupported capture width");
        };
    float captureFont =
        switch (args.getString("capture_font", "1.0")) {
          case "1.0" -> 1f;
          case "1.3" -> 1.3f;
          default -> throw new IllegalArgumentException("unsupported capture font");
        };

    Context context = test.getTargetContext();
    String namespace = "ranking-wait-fixture-" + UUID.randomUUID();
    SharedPreferences prefs = context.getSharedPreferences(namespace, Context.MODE_PRIVATE);
    File xml = new File(context.getApplicationInfo().dataDir, "shared_prefs/" + namespace + ".xml");
    List<File> files = new ArrayList<>();
    MainActivity activity = null;
    RankingStore cleanupStore = null;
    Map<String, Object> originalFields = new LinkedHashMap<>();
    Commit held = new Commit(true);
    boolean captureMatrix = capture && probe == Probe.NONE;
    CountDownLatch releaseInitial = new CountDownLatch(captureMatrix ? 1 : 0);
    CountDownLatch releaseRetry = new CountDownLatch(captureMatrix ? 1 : 0);
    try {
      seedXml(prefs, entries);
      long xmlBytes = xml.length();
      require(xmlBytes > 0 && prefs.getAll().size() == entries, "exact fixture XML population");
      List<PhotoRepository.Photo> photos = fixturePhotos(context, files);
      if (probe == Probe.CATALOG)
        require(
            prefs
                .edit()
                .putInt(
                    "single-best-v1-" + PhotoRepository.digest(ArchiveStore.key(photos.get(0))), 2)
                .commit(),
            "seed isolated malformed rating before opening owner");
      AtomicReference<Commit> next = new AtomicReference<>();
      SharedPreferences instrumented = observe(prefs, next);
      RankingStore observed =
          new RankingStore(
              captureMatrix
                  ? delayFailedLoads(instrumented, releaseInitial, releaseRetry)
                  : instrumented);
      cleanupStore = observed;
      if (probe == Probe.CLEANUP) return proveCleanupDrain(observed, prefs, next);
      activity =
          (MainActivity)
              test.startActivitySync(
                  new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      GalleryScreenChecks.finishInitialIndex(test, activity);
      GalleryScreenChecks.settle(test, activity);
      int expectedWidth = 0;
      if (capture) {
        int displayWidth = activity.getResources().getConfiguration().screenWidthDp;
        expectedWidth = captureWidth == 0 ? displayWidth : captureWidth;
        GalleryScreenChecks.portraitFixture(test, activity, expectedWidth, captureFont);
      }
      if (!captureMatrix) observed.awaitReady();
      installFixture(test, activity, originalFields, photos, observed);
      String profile = (captureWidth == 0 ? "native" : "320") + "-" + captureFont;
      String captureProfile = capture ? profile : null;
      if (probe == Probe.ZOOM)
        return proveExpandedComparison(
                test, activity, prefs, observed, next, photos, captureProfile)
            + activityDimensions(test, activity, expectedWidth, captureFont)
            + "\n";
      if (probe == Probe.CATALOG)
        return proveCatalogReadFailure(
                test, activity, prefs, observed, photos.get(0), captureProfile)
            + activityDimensions(test, activity, expectedWidth, captureFont)
            + "\n";
      if (capture)
        captureReadStates(
            test,
            activity,
            observed,
            releaseInitial,
            releaseRetry,
            profile,
            entries,
            prefs,
            photos);
      String read = heldRead(test, activity, prefs, next, held, baseline, photos.get(0));
      if (capture) {
        captureScreen(test, activity, "ranking-star-ready-on-" + profile);
        captureStarSave(test, activity, prefs, next, photos.get(0), profile);
      }
      String choose = choose(test, activity, prefs, next, baseline, photos);
      String shareTrace = "";
      if (capture) {
        captureScreen(test, activity, "ranking-compare-success-" + profile);
        shareTrace =
            captureComparisonStates(test, activity, prefs, observed, next, photos, profile);
      }
      String dimensions = activityDimensions(test, activity, expectedWidth, captureFont);
      return "PASS ranking persistence waits baseline="
          + baseline
          + " entries="
          + entries
          + " singletonEntries="
          + entries / 2
          + " burstEntries="
          + entries / 2
          + " xmlBytesBefore="
          + xmlBytes
          + " xmlBytesAfter="
          + xml.length()
          + " photos=4 burstMembers=3 jpegWidth=320 jpegHeight=240 prefsWarm=true"
          + " firstPairReady=true "
          + dimensions
          + "\n"
          + read
          + "\n"
          + choose
          + "\n"
          + shareTrace;
    } finally {
      releaseInitial.countDown();
      releaseRetry.countDown();
      held.release();
      try {
        if (activity != null) restoreAndFinish(test, activity, originalFields);
      } finally {
        try {
          drainRankingWriter(cleanupStore);
          require(
              context.deleteSharedPreferences(namespace), "isolated ranking preferences removed");
        } finally {
          try {
            for (File file : files) Files.deleteIfExists(file.toPath());
          } finally {
            requireEmptyData(context);
          }
        }
      }
    }
  }

  private static String proveExpandedComparison(
      Instrumentation test,
      MainActivity activity,
      SharedPreferences prefs,
      RankingStore store,
      AtomicReference<Commit> next,
      List<PhotoRepository.Photo> photos,
      String profile)
      throws Exception {
    List<String> ids = new ArrayList<>();
    for (int member : BURST) ids.add(ArchiveStore.key(photos.get(member)));
    ui(
        test,
        () -> {
          set(activity, "burstMembers", BURST);
          set(activity, "burstPosition", 0);
          call(activity, "openRanking");
        });
    waitFor(test, () -> comparisonVisible(activity, "B:"), "initial pair ready to zoom");
    RankingStore.Burst original = store.snapshot().burst(ids);
    PhotoRepository.Photo upper = photoForRankKey(photos, original.ranking().upper());
    long revision = store.snapshot().revision();

    Commit held = new Commit(true, false, CAPTURE_GATE_MS);
    next.set(held);
    try {
      ui(test, () -> require(choice(activity).performClick(), "start choice before expanding"));
      require(held.entered.await(2, TimeUnit.SECONDS), "comparison writer held");
      expandUpper(test, activity);
      waitFor(
          test,
          () ->
              expandedMatches(activity, upper, true, false)
                  && comparisonStatus(activity, "順位を保存しています"),
          "expanded held choice shows old candidate and busy state");
      if (profile != null) captureScreen(test, activity, "ranking-zoom-held-" + profile);
      require(held.delegateStart == 0 && !held.timedOut, "expand completed during held write");
    } finally {
      held.release();
    }
    require(held.done.await(5, TimeUnit.SECONDS) && held.success, "held choice committed");
    waitFor(
        test,
        () -> expandedMatches(activity, upper, true, true) && !comparisonStatusShown(activity),
        "expanded choice stayed busy after confirmed commit");
    if (profile != null) captureScreen(test, activity, "ranking-zoom-settled-" + profile);
    ui(test, activity::onBackPressed);
    waitFor(test, () -> comparisonVisible(activity, "B:0"), "Back uses committed pair");
    require(
        store.snapshot().revision() == revision + 1
            && "B:0".equals(readSavedBurst(prefs, ids).journal),
        "zoom and Back did not write again");

    RankingStore other = new RankingStore(store);
    publishBurst(other, ids, "B:");
    waitFor(
        test, () -> comparisonVisible(activity, "B:"), "restored initial pair from another owner");
    expandUpper(test, activity);
    waitFor(
        test, () -> expandedMatches(activity, upper, true, true), "first candidate still expanded");
    publishBurst(other, ids, "B:0");
    waitFor(
        test,
        () ->
            expandedMatches(activity, upper, true, true)
                && "B:0".equals(((PhotoRanking) get(activity, "ranking")).journal),
        "publication refreshed the expanded candidate's role");
    ui(
        test,
        () ->
            require(
                expandedChoice(activity).performClick(),
                "choose the displayed photo in its new role"));
    waitFor(
        test,
        () ->
            expandedMatches(activity, upper, false, false)
                && "B:01".equals(((PhotoRanking) get(activity, "ranking")).journal),
        "expanded choice applied to the displayed lower candidate and then hid");
    require(
        "B:01".equals(readSavedBurst(prefs, ids).journal),
        "correct committed photo chosen after publication");
    ui(test, activity::onBackPressed);
    waitFor(test, () -> rankingResultVisible(activity, "B:01"), "Back shows the committed result");

    String winnerTag = "result-hero-" + ArchiveStore.key(upper);
    ui(
        test,
        () ->
            require(
                ((View) get(activity, "root")).findViewWithTag(winnerTag).performClick(),
                "open native result winner"));
    waitFor(
        test,
        () -> expandedMatches(activity, upper, false, false),
        "result photo has no comparison action");
    revision = store.snapshot().revision();
    publishBurst(other, ids, "B:0");
    waitFor(
        test,
        () -> expandedMatches(activity, upper, true, true),
        "published undo updates expanded result");
    if (profile != null) captureScreen(test, activity, "ranking-zoom-result-updated-" + profile);
    ui(test, activity::onBackPressed);
    waitFor(
        test, () -> comparisonVisible(activity, "B:0"), "Back uses latest committed comparison");
    require(store.snapshot().revision() == revision + 1, "expanded result and Back did not write");
    return "PASS rankingZoom heldCommit=true heldBusyCleared=true expandedCandidateRole=true "
        + "expandedResultPublication=true backCommitted=true extraWrites=0\n";
  }

  private static void expandUpper(Instrumentation test, MainActivity activity) throws Exception {
    ui(
        test,
        () -> {
          Button zoom = TagDetailChecks.button((View) get(activity, "root"), "Aを拡大");
          require(
              zoom != null && zoom.isEnabled() && zoom.performClick(),
              "native comparison zoom enabled");
        });
  }

  private static Button expandedChoice(MainActivity activity) {
    View root = (View) get(activity, "root");
    if (!(root instanceof android.view.ViewGroup content)
        || !(content.getChildAt(0) instanceof PhotoViewerUi viewer)) return null;
    return viewer.footer.getChildAt(0) instanceof Button button ? button : null;
  }

  private static boolean expandedMatches(
      MainActivity activity, PhotoRepository.Photo photo, boolean visible, boolean enabled) {
    View root = (View) get(activity, "root");
    if (!(root instanceof android.view.ViewGroup content)
        || !(content.getChildAt(0) instanceof PhotoViewerUi viewer)
        || viewer.photo != photo) return false;
    Button action = expandedChoice(activity);
    return visible
        ? action != null && action.isShown() && action.isEnabled() == enabled
        : action == null || !action.isShown();
  }

  private static boolean rankingResultVisible(MainActivity activity, String journal) {
    PhotoRanking ranking = (PhotoRanking) get(activity, "ranking");
    return Boolean.TRUE.equals(get(activity, "rankingScreen"))
        && ranking.done()
        && journal.equals(ranking.journal)
        && ((View) get(activity, "root")).findViewWithTag("result-hero-" + ranking.best().get(0))
            != null;
  }

  private static void publishBurst(RankingStore owner, List<String> ids, String journal)
      throws Exception {
    RankingStore.Burst previous = owner.snapshot().burst(ids);
    require(
        owner
                .submitBurst(previous, () -> new PhotoRanking(previous.ids(), journal))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS)
                .outcome()
            == RankingStore.Outcome.SAVED,
        "other ranking handle committed new journal");
  }

  private static String proveCatalogReadFailure(
      Instrumentation test,
      MainActivity activity,
      SharedPreferences prefs,
      RankingStore owner,
      PhotoRepository.Photo malformedPhoto,
      String profile)
      throws Exception {
    String uri = malformedPhoto.uri.toString();
    try (GalleryIndex catalog = new GalleryIndex(activity)) {
      require(catalog.load(null, "すべて").photos.isEmpty(), "catalog probe requires empty index");
      ui(
          test,
          () -> {
            set(activity, "photos", List.of());
            set(activity, "bundles", List.of());
            set(activity, "rankFilter", 1);
            call(activity, "gallery");
          });
      waitFor(
          test,
          () -> ((View) get(activity, "rankEmpty")).isShown() && catalogRetry(activity) == null,
          "active rank filter started with honest empty state");

      ContentValues row = new ContentValues();
      row.put("bundle", 1);
      row.put("camera", "その他");
      row.put("uri", uri);
      row.put("name", malformedPhoto.name);
      row.put("folder", malformedPhoto.folder);
      row.put("size", malformedPhoto.size);
      row.put("modified", malformedPhoto.modified);
      row.put("taken", malformedPhoto.taken);
      long id = catalog.getWritableDatabase().insertOrThrow("photos", null, row);
      try {
        RankingStore.Snapshot unchanged = owner.snapshot();
        Map<String, ?> raw = new HashMap<>(prefs.getAll());
        ui(test, () -> call(activity, "loadIndexedPhotos"));
        waitFor(
            test,
            () -> ((List<?>) get(activity, "photos")).size() == 1 && catalogReadFailed(activity),
            "completed catalog reload must display ranking read failure");
        if (profile != null)
          captureScreen(test, activity, "ranking-catalog-read-failure-" + profile);
        ui(test, () -> require(catalogRetry(activity).performClick(), "explicit rank read retry"));
        waitFor(
            test,
            () -> catalogReadFailed(activity),
            "malformed entry stays readable as a failure after retry");
        require(
            owner.snapshot() == unchanged && raw.equals(prefs.getAll()), "retry never wrote ranks");
      } finally {
        require(
            catalog
                    .getWritableDatabase()
                    .delete("photos", "ordinal=? AND uri=?", new String[] {Long.toString(id), uri})
                == 1,
            "fixture removed its exact catalog row");
      }
    }
    return "PASS rankingCatalog activeFilter=true malformedKey=true failureVisible=true"
        + " readOnlyRetry=true\n";
  }

  private static Button catalogRetry(MainActivity activity) {
    return ((View) get(activity, "root")).findViewWithTag("catalog-ranking-retry");
  }

  private static boolean catalogReadFailed(MainActivity activity) {
    TextView message = ((View) get(activity, "root")).findViewWithTag("catalog-ranking-failure");
    Button retry = catalogRetry(activity);
    return visibleLabel(message, "順位を読み込めませんでした")
        && visibleLabel(retry, "順位を読み直す")
        && hasHitBounds(retry)
        && retry.createAccessibilityNodeInfo().isClickable();
  }

  static void drainRankingWriter(RankingStore owner) throws Exception {
    if (owner != null) rankingWriterBarrier(owner).get(8, TimeUnit.SECONDS);
  }

  private static CompletableFuture<Void> rankingWriterBarrier(RankingStore owner) throws Exception {
    // Queue after admitted writes and repairs. Waiting on Editor.commit alone misses compensation.
    Field backend = RankingStore.class.getDeclaredField("backend");
    backend.setAccessible(true);
    Object lane = backend.get(owner);
    Field worker = lane.getClass().getDeclaredField("worker");
    worker.setAccessible(true);
    return CompletableFuture.runAsync(() -> {}, (Executor) worker.get(lane));
  }

  private static String proveCleanupDrain(
      RankingStore owner, SharedPreferences prefs, AtomicReference<Commit> next) throws Exception {
    owner.awaitReady();
    String key = "rank-fixture-cleanup";
    Commit fail = new Commit(true, true, CAPTURE_GATE_MS);
    Commit compensation = new Commit(true, false, CAPTURE_GATE_MS);
    next.set(fail);
    var write = owner.submitSingleBest(key, false, true).toCompletableFuture();
    CompletableFuture<Void> barrier;
    try {
      require(
          fail.entered.await(2, TimeUnit.SECONDS), "cleanup fixture held admitted primary write");
      next.set(compensation);
      barrier = rankingWriterBarrier(owner);
      fail.release();
      require(compensation.entered.await(2, TimeUnit.SECONDS), "cleanup reached held compensation");
      require(!barrier.isDone() && !write.isDone(), "cleanup waits for admitted compensation");
    } finally {
      fail.release();
      compensation.release();
    }
    barrier.get(8, TimeUnit.SECONDS);
    require(
        write.get(1, TimeUnit.SECONDS).outcome() == RankingStore.Outcome.WRITE_FAILED
            && compensation.success
            && !prefs.contains("single-best-v1-" + PhotoRepository.digest(key)),
        "drained compensation restored absent durable value before deletion");
    return "PASS rankingCleanup writerDrained=true compensationHeld=true"
        + " restoredBeforeDelete=true\n";
  }

  private static SharedPreferences delayFailedLoads(
      SharedPreferences actual, CountDownLatch initial, CountDownLatch retry) {
    List<CountDownLatch> gates = List.of(initial, retry);
    var load = new java.util.concurrent.atomic.AtomicInteger();
    return (SharedPreferences)
        Proxy.newProxyInstance(
            SharedPreferences.class.getClassLoader(),
            new Class<?>[] {SharedPreferences.class},
            (proxy, method, args) -> {
              if (method.getName().equals("getAll")) {
                int attempt = load.getAndIncrement();
                if (attempt < gates.size()) {
                  require(
                      gates.get(attempt).await(18, TimeUnit.SECONDS), "read capture gate expired");
                  throw new IllegalStateException("Synthetic rank initialization failure");
                }
              }
              return invoke(method, actual, args);
            });
  }

  private static void captureReadStates(
      Instrumentation test,
      MainActivity activity,
      RankingStore store,
      CountDownLatch releaseInitial,
      CountDownLatch releaseRetry,
      String profile,
      int entries,
      SharedPreferences prefs,
      List<PhotoRepository.Photo> photos)
      throws Exception {
    ViewerScreenChecks.open(test, activity, 0);
    ui(
        test,
        () -> {
          Button pending = star(activity);
          require(
              pending != null
                  && !pending.isEnabled()
                  && "順位を読み込み中".contentEquals(pending.getStateDescription())
                  && !pending.createAccessibilityNodeInfo().isCheckable(),
              "unknown star is not unselected");
        });
    captureScreen(test, activity, "ranking-star-pending-" + profile);
    ui(
        test,
        () -> {
          activity.onBackPressed();
          openWinnerSelection(activity, photos);
        });
    captureScreen(
        test,
        activity,
        activity.getWindow().getDecorView(),
        () -> pendingShare(activity, photos),
        "ranking-share-pending-" + profile);
    ui(
        test,
        () -> {
          int before = (int) get(activity, "shareAttempt");
          call(activity, "shareSelection");
          require(
              before == (int) get(activity, "shareAttempt")
                  && get(activity, "shareProgress") == null
                  && pendingShare(activity, photos),
              "pending share kept selection and exposed no inferred target");
          call(activity, "endSelection");
        });
    ViewerScreenChecks.open(test, activity, 0);
    releaseInitial.countDown();
    View viewerDecor = activity.getWindow().getDecorView();
    captureScreen(
        test,
        activity,
        viewerDecor,
        () -> {
          Button button = star(activity);
          return button != null
              && button.isEnabled()
              && "読み込み失敗".contentEquals(button.getStateDescription())
              && !button.createAccessibilityNodeInfo().isCheckable()
              && hasHitBounds(button)
              && viewerRecovery(activity, "順位を読み込めませんでした", "順位を読み直す");
        },
        "ranking-star-read-failure-" + profile);
    ViewerScreenChecks.open(test, activity, BURST.get(0));
    ui(
        test,
        () ->
            require(viewerRetry(activity) == null, "read failure belongs to the singleton viewer"));
    ViewerScreenChecks.open(test, activity, 0);
    Map<String, ?> beforeReadRetry = new HashMap<>(prefs.getAll());
    ui(test, () -> require(viewerRetry(activity).performClick(), "explicit read retry"));
    captureScreen(
        test,
        activity,
        viewerDecor,
        () -> {
          Button button = star(activity);
          return button != null
              && !button.isEnabled()
              && "順位を読み込み中".contentEquals(button.getStateDescription())
              && !button.createAccessibilityNodeInfo().isCheckable()
              && !viewerRetry(activity).isShown();
        },
        "ranking-star-read-retry-pending-" + profile);
    require(
        beforeReadRetry.size() == entries && beforeReadRetry.equals(prefs.getAll()),
        "read retry did not write a rank");
    ui(
        test,
        () -> {
          activity.onBackPressed();
          call(activity, "chooseFilter");
        });
    PhotoFilterSheet sheet = (PhotoFilterSheet) get(activity, "rankFilterSheet");
    View decor = sheet.getWindow().getDecorView();
    Button apply = decor.findViewWithTag("photo-filter-apply");
    ui(
        test,
        () -> require(TagDetailChecks.button(decor, "1位").performClick(), "rank filter draft"));
    waitFor(
        test,
        () -> !apply.isEnabled() && "読み込み中".contentEquals(apply.getText()),
        "pending rank filter has no count");
    captureScreen(
        test,
        activity,
        decor,
        () -> visibleLabel(apply, "読み込み中"),
        "ranking-filter-pending-" + profile);
    releaseRetry.countDown();
    waitFor(
        test,
        () -> apply.isEnabled() && "件数を再確認".contentEquals(apply.getText()),
        "failed rank filter offers retry");
    captureScreen(
        test,
        activity,
        decor,
        () ->
            visibleLabel(apply, "件数を再確認")
                && visibleLabel(
                    decor.findViewWithTag("photo-filter-count-failure"), "件数を読み込めませんでした"),
        "ranking-filter-failed-" + profile);
    require(store.snapshot().phase() == RankingStore.Phase.FAILED, "fixture initialization failed");
    ui(test, () -> require(apply.performClick(), "retry filter read"));
    waitFor(
        test,
        () -> apply.isEnabled() && "0件を表示".contentEquals(apply.getText()),
        "ready empty rank count appears only after retry");
    captureScreen(
        test,
        activity,
        decor,
        () ->
            visibleLabel(apply, "0件を表示")
                && apply.isEnabled()
                && !decor.findViewWithTag("photo-filter-count-failure").isShown(),
        "ranking-filter-recovered-" + profile);
    dismissFilter(test, activity, sheet);
    require(
        prefs.getAll().size() == entries && (int) get(activity, "rankFilter") == 0,
        "filter retry saved nothing and did not apply draft");
    ViewerScreenChecks.open(test, activity, 0);
    captureScreen(
        test,
        activity,
        activity.getWindow().getDecorView(),
        () -> starState(activity, true, false, "未指定"),
        "ranking-star-ready-off-" + profile);
  }

  private static void dismissFilter(
      Instrumentation test, MainActivity activity, PhotoFilterSheet sheet) throws Exception {
    View decor = sheet.getWindow().getDecorView();
    ui(test, sheet::dismiss);
    waitFor(
        test,
        () -> !decor.isAttachedToWindow() && activity.hasWindowFocus(),
        "filter dismissed before activity capture");
  }

  private static void openWinnerSelection(
      MainActivity activity, List<PhotoRepository.Photo> photos) {
    call(activity, "beginSelection");
    require(Boolean.TRUE.equals(get(activity, "selecting")), "synthetic gallery entered selection");
    selected(activity).addAll(burstSelection(photos));
    call(activity, "updateSelection");
  }

  @SuppressWarnings("unchecked")
  private static Set<String> selected(MainActivity activity) {
    return (Set<String>) get(activity, "selectedPhotos");
  }

  private static Set<String> burstSelection(List<PhotoRepository.Photo> photos) {
    return Set.of(
        photos.get(BURST.get(0)).uri.toString(),
        photos.get(BURST.get(1)).uri.toString(),
        photos.get(BURST.get(2)).uri.toString());
  }

  private static boolean pendingShare(MainActivity activity, List<PhotoRepository.Photo> photos) {
    return selected(activity).equals(burstSelection(photos))
        && Boolean.TRUE.equals(get(activity, "bestOnly"))
        && ((View) get(activity, "selectionRankStatus")).isShown()
        && !((View) get(activity, "selectionCount")).isShown()
        && !((View) get(activity, "selectionShare")).isEnabled();
  }

  private static boolean starState(
      MainActivity activity, boolean enabled, boolean checked, String state) {
    Button button = star(activity);
    if (button == null
        || !button.isShown()
        || button.isSelected() != checked
        || !state.contentEquals(button.getStateDescription())
        || !hasHitBounds(button)) return false;
    var node = button.createAccessibilityNodeInfo();
    return node.isEnabled() == enabled && node.isCheckable() && node.isChecked() == checked;
  }

  private static boolean hasHitBounds(View button) {
    Rect bounds = new Rect();
    return button.getGlobalVisibleRect(bounds)
        && bounds.height() >= GalleryUi.dp(button.getContext(), 48)
        && bounds.width() >= GalleryUi.dp(button.getContext(), 48);
  }

  private static void captureStarSave(
      Instrumentation test,
      MainActivity activity,
      SharedPreferences prefs,
      AtomicReference<Commit> next,
      PhotoRepository.Photo photo,
      String profile)
      throws Exception {
    String key = "single-best-v1-" + PhotoRepository.digest(ArchiveStore.key(photo));
    Commit fail = new Commit(true, true, CAPTURE_GATE_MS);
    next.set(fail);
    Bitmap busy;
    try {
      ui(test, () -> require(star(activity).performClick(), "start unmarking the committed star"));
      require(fail.entered.await(2, TimeUnit.SECONDS), "singleton writer entered");
      busy =
          settledScreenshot(
              test,
              activity.getWindow().getDecorView(),
              () -> starState(activity, false, true, "保存中"));
      ui(test, () -> star(activity).performClick());
      require(
          !fail.timedOut && fail.delegateStart == 0 && "1".equals(prefs.getString(key, null)),
          "held star did not expose or publish its proposed value");
    } finally {
      fail.release();
    }
    writeCapture(activity, busy, "ranking-star-busy-" + profile);
    View decor = activity.getWindow().getDecorView();
    captureScreen(
        test,
        activity,
        decor,
        () ->
            starState(activity, true, true, "保存失敗")
                && viewerRecovery(activity, "順位を保存できませんでした", "保存を再試行"),
        "ranking-star-failure-" + profile);
    require("1".equals(prefs.getString(key, null)), "failed star kept committed checked value");
    ui(test, () -> require(viewerRetry(activity).performClick(), "explicit save retry"));
    captureScreen(
        test,
        activity,
        decor,
        () -> starState(activity, true, false, "未指定") && !viewerRetry(activity).isShown(),
        "ranking-star-save-recovered-" + profile);
    require("0".equals(prefs.getString(key, null)), "fresh tap cleared the saved star");
  }

  private static Button viewerRetry(MainActivity activity) {
    return ((View) get(activity, "root")).findViewWithTag("viewer-ranking-retry");
  }

  private static boolean viewerRecovery(MainActivity activity, String message, String retryLabel) {
    TextView notice = ((View) get(activity, "root")).findViewWithTag("viewer-ranking-recovery");
    Button retry = viewerRetry(activity);
    return visibleLabel(notice, message)
        && notice.getAccessibilityLiveRegion() == View.ACCESSIBILITY_LIVE_REGION_POLITE
        && visibleLabel(retry, retryLabel)
        && hasHitBounds(retry)
        && retry.createAccessibilityNodeInfo().isEnabled()
        && retry.createAccessibilityNodeInfo().isClickable();
  }

  private static String captureComparisonStates(
      Instrumentation test,
      MainActivity activity,
      SharedPreferences prefs,
      RankingStore store,
      AtomicReference<Commit> next,
      List<PhotoRepository.Photo> photos,
      String profile)
      throws Exception {
    List<String> ids = new ArrayList<>();
    for (int member : BURST) ids.add(ArchiveStore.key(photos.get(member)));
    View decor = activity.getWindow().getDecorView();
    Commit fail = new Commit(true, true, CAPTURE_GATE_MS);
    next.set(fail);
    Bitmap busy;
    try {
      ui(test, () -> require(choice(activity).performClick(), "start second comparison"));
      require(fail.entered.await(2, TimeUnit.SECONDS), "comparison writer entered");
      busy =
          settledScreenshot(
              test,
              decor,
              () ->
                  comparisonStatus(activity, "順位を保存しています")
                      && hasHitBounds(choice(activity))
                      && !choice(activity).createAccessibilityNodeInfo().isEnabled()
                      && ((List<?>) get(activity, "rankingActions"))
                          .stream().allMatch(view -> !((View) view).isEnabled())
                      && "B:0".equals(((PhotoRanking) get(activity, "ranking")).journal));
      ui(test, () -> choice(activity).performClick());
      require(!fail.timedOut && fail.delegateStart == 0, "capture during held commit");
    } finally {
      fail.release();
    }
    writeCapture(activity, busy, "ranking-compare-busy-" + profile);
    waitFor(
        test,
        () -> !Boolean.TRUE.equals(get(activity, "rankingBusy")),
        "comparison failure settled");
    require("B:0".equals(readSavedBurst(prefs, ids).journal), "rollback kept original comparison");
    captureScreen(
        test,
        activity,
        decor,
        () ->
            comparisonVisible(activity, "B:0")
                && comparisonStatus(activity, "順位を保存できませんでした。もう一度選んでください"),
        "ranking-compare-failure-" + profile);

    // Queue a change from another handle in the same UI turn, then activate the still-shown pair.
    RankingStore.Burst old = store.snapshot().burst(ids);
    RankingStore other = new RankingStore(store);
    ui(
        test,
        () -> {
          other.submitBurst(old, () -> new PhotoRanking(old.ids(), "B:"));
          require(choice(activity).performClick(), "activate now-stale pair");
        });
    waitFor(
        test,
        () ->
            !Boolean.TRUE.equals(get(activity, "rankingBusy")) && comparisonVisible(activity, "B:"),
        "comparison conflict refreshes latest pair");
    require(
        "B:".equals(readSavedBurst(prefs, ids).journal),
        "stale action did not overwrite other handle");
    captureScreen(
        test,
        activity,
        decor,
        () ->
            comparisonVisible(activity, "B:")
                && comparisonStatus(activity, "順位が変わりました。確認して選び直してください"),
        "ranking-compare-conflict-" + profile);
    ui(
        test,
        () -> require(choice(activity).performClick(), "select again after seeing latest pair"));
    captureScreen(
        test,
        activity,
        decor,
        () -> comparisonVisible(activity, "B:0") && !comparisonStatusShown(activity),
        "ranking-compare-recovered-" + profile);

    Commit finishing = new Commit(true, false, CAPTURE_GATE_MS);
    next.set(finishing);
    try {
      ui(test, () -> require(choice(activity).performClick(), "start final comparison"));
      require(finishing.entered.await(2, TimeUnit.SECONDS), "final comparison writer entered");
      ui(test, activity::onBackPressed);
      captureScreen(
          test,
          activity,
          decor,
          () ->
              !Boolean.TRUE.equals(get(activity, "rankingScreen"))
                  && !comparisonStatusShown(activity),
          "ranking-compare-back-while-busy-" + profile);
      require(
          finishing.delegateStart == 0 && !finishing.timedOut,
          "Back rendered while persistence is held");
    } finally {
      finishing.release();
    }
    require(finishing.done.await(5, TimeUnit.SECONDS) && finishing.success, "write survived Back");
    waitFor(test, () -> !Boolean.TRUE.equals(get(activity, "rankingBusy")), "Back write published");
    require(
        "B:00".equals(readSavedBurst(prefs, ids).journal), "Back did not discard accepted choice");
    return captureChangedShare(test, activity, other, ids, photos, profile);
  }

  private static String captureChangedShare(
      Instrumentation test,
      MainActivity activity,
      RankingStore store,
      List<String> ids,
      List<PhotoRepository.Photo> photos,
      String profile)
      throws Exception {
    ui(test, () -> openWinnerSelection(activity, photos));
    Button share = (Button) get(activity, "selectionShare");
    waitFor(test, share::isEnabled, "ready winner can be shared");
    ui(
        test,
        () -> {
          require(share.performClick(), "open actual winner share menu");
          require(get(activity, "shareDialog") instanceof ShareUi.Menu, "complete winner menu");
        });
    ShareUi.Menu menu = (ShareUi.Menu) get(activity, "shareDialog");
    waitFor(test, menu.continueButton::isEnabled, "old reviewed share action available");
    int initialAttempt = (int) get(activity, "shareAttempt");
    RankingStore.Burst original = store.snapshot().burst(ids);
    require(
        menu.previewPhotos.equals(List.of(photoForRankKey(photos, original.ids().get(2)))),
        "reviewed only the old winner");
    captureScreen(
        test,
        activity,
        menu.getWindow().getDecorView(),
        () -> menu.isShowing() && menu.continueButton.isEnabled(),
        "ranking-share-old-review-" + profile);
    long oldPreviewNs = now();
    RankingStore.WriteResult changed =
        store
            .submitBurst(original, () -> new PhotoRanking(original.ids(), "B:11"))
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS);
    require(changed.outcome() == RankingStore.Outcome.SAVED, "other handle changed the winner");
    long rankChangedNs = now();

    IntentFilter outbound = new IntentFilter();
    outbound.addAction(Intent.ACTION_CHOOSER);
    outbound.addAction(Intent.ACTION_SEND);
    outbound.addAction(Intent.ACTION_SEND_MULTIPLE);
    outbound.addCategory(Intent.CATEGORY_DEFAULT);
    outbound.addDataType("*/*");
    var sharing = test.addMonitor(outbound, null, true);
    var chooser = test.addMonitor(new IntentFilter(Intent.ACTION_CHOOSER), null, true);
    View menuDecor = menu.getWindow().getDecorView();
    try {
      ui(
          test,
          () -> {
            int before = (int) get(activity, "shareAttempt");
            require(menu.continueButton.performClick(), "continue with formerly reviewed target");
            require(
                before == (int) get(activity, "shareAttempt")
                    && get(activity, "shareProgress") == null
                    && selected(activity).equals(burstSelection(photos)),
                "obsolete reviewed target was rejected before export, with exact selection"
                    + " retained");
          });
      waitFor(
          test,
          () -> !menuDecor.isAttachedToWindow() && activity.hasWindowFocus(),
          "old share review dismissed");
      captureScreen(
          test,
          activity,
          activity.getWindow().getDecorView(),
          () -> {
            TextView notice = (TextView) get(activity, "selectionShareNotice");
            return visibleLabel(notice, "共有する写真が変わりました。もう一度「共有」を押して確認してください")
                && notice.getAccessibilityLiveRegion() == View.ACCESSIBILITY_LIVE_REGION_POLITE
                && selected(activity).equals(burstSelection(photos))
                && (int) get(activity, "shareAttempt") == initialAttempt
                && get(activity, "shareProgress") == null
                && get(activity, "shareDialog") == menu;
          },
          "ranking-share-target-changed-" + profile);
      long blockedNs = now();
      waitFor(test, share::isEnabled, "changed winner requires a new review");
      ui(test, () -> require(share.performClick(), "user reopens review explicitly"));
      ShareUi.Menu renewed = (ShareUi.Menu) get(activity, "shareDialog");
      require(
          renewed != menu
              && renewed.previewPhotos.equals(
                  List.of(photoForRankKey(photos, original.ids().get(0)))),
          "fresh review shows only the new winner");
      captureScreen(
          test,
          activity,
          renewed.getWindow().getDecorView(),
          () ->
              renewed.isShowing()
                  && renewed.continueButton.isShown()
                  && !((View) get(activity, "selectionShareNotice")).isShown()
                  && selected(activity).equals(burstSelection(photos))
                  && get(activity, "shareProgress") == null,
          "ranking-share-renewed-review-" + profile);
      long renewedNs = now();
      int attemptDelta = (int) get(activity, "shareAttempt") - initialAttempt;
      int outboundHits = sharing.getHits() + chooser.getHits();
      require(
          oldPreviewNs < rankChangedNs
              && rankChangedNs < blockedNs
              && blockedNs < renewedNs
              && attemptDelta == 0
              && outboundHits == 0,
          "ordered review completed without automatic export or handoff");
      ui(test, renewed::dismiss);
      proveShareSnapshot(test, activity, store, ids, renewed.previewPhotos.get(0));
      return "shareReview oldPreviewNs="
          + oldPreviewNs
          + " rankChangedNs="
          + rankChangedNs
          + " staleContinueBlockedNs="
          + blockedNs
          + " explicitNewPreviewNs="
          + renewedNs
          + " exactSelection=true selectedCount="
          + BURST.size()
          + " oldPreviewCount="
          + menu.previewPhotos.size()
          + " newPreviewCount="
          + renewed.previewPhotos.size()
          + " noticeVisible=true attemptDelta="
          + attemptDelta
          + " outboundHits="
          + outboundHits
          + " newContinueExecuted=false previewSnapshotConsistent=true\n";
    } finally {
      if (get(activity, "shareProgress") == null) {
        test.removeMonitor(sharing);
        test.removeMonitor(chooser);
      }
    }
  }

  private static void proveShareSnapshot(
      Instrumentation test,
      MainActivity activity,
      RankingStore owner,
      List<String> ids,
      PhotoRepository.Photo expectedWinner)
      throws Exception {
    Method resultMethod = MainActivity.class.getDeclaredMethod("selectionResult");
    resultMethod.setAccessible(true);
    AtomicReference<Object> selected = new AtomicReference<>();
    ui(
        test,
        () -> {
          try {
            selected.set(resultMethod.invoke(activity));
          } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
          }
        });
    Field snapshotField = selected.get().getClass().getDeclaredField("ranks");
    Field photosField = selected.get().getClass().getDeclaredField("photos");
    snapshotField.setAccessible(true);
    photosField.setAccessible(true);
    RankingStore.Snapshot resolved = (RankingStore.Snapshot) snapshotField.get(selected.get());
    List<?> resolvedPhotos = List.copyOf((List<?>) photosField.get(selected.get()));
    require(
        resolvedPhotos.equals(List.of(expectedWinner)),
        "frozen review selected the current winner");
    publishBurst(owner, ids, "B:00");
    require(
        !owner.snapshot().burst(ids).ranking().best().contains(ArchiveStore.key(expectedWinner)),
        "a different winner published before frozen review construction");
    Method menuMethod =
        MainActivity.class.getDeclaredMethod(
            "shareMenu", List.class, RankingStore.Snapshot.class, BooleanSupplier.class);
    menuMethod.setAccessible(true);
    ui(
        test,
        () -> {
          try {
            menuMethod.invoke(activity, resolvedPhotos, resolved, (BooleanSupplier) () -> false);
          } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
          }
          ShareUi.Menu frozen = (ShareUi.Menu) get(activity, "shareDialog");
          require(
              frozen.previewPhotos.equals(resolvedPhotos)
                  && frozen.previews.size() == 1
                  && ((android.view.ViewGroup) frozen.previews.get(0).getParent()).getChildCount()
                      == 2,
              "the frozen preview photo and best mark use the same resolution");
          frozen.dismiss();
        });
  }

  private static PhotoRepository.Photo photoForRankKey(
      List<PhotoRepository.Photo> photos, String key) throws Exception {
    for (int member : BURST) {
      PhotoRepository.Photo photo = photos.get(member);
      if (key.equals(ArchiveStore.key(photo))) return photo;
    }
    throw new AssertionError("expected generated winner is absent");
  }

  private static boolean comparisonStatusShown(MainActivity activity) {
    View status = ((View) get(activity, "root")).findViewWithTag("ranking-save-status");
    return status != null && status.isShown();
  }

  private static boolean comparisonStatus(MainActivity activity, String message) {
    TextView status = ((View) get(activity, "root")).findViewWithTag("ranking-save-status");
    return visibleLabel(status, message)
        && status.getAccessibilityLiveRegion() == View.ACCESSIBILITY_LIVE_REGION_POLITE;
  }

  private static boolean visibleLabel(TextView view, String message) {
    if (view == null || !view.isShown() || !message.contentEquals(view.getText())) return false;
    Rect bounds = new Rect();
    return view.getGlobalVisibleRect(bounds)
        && bounds.height() == view.getHeight()
        && view.getLayout() != null
        && view.getLineCount() > 0
        && view.getLayout().getEllipsisCount(view.getLineCount() - 1) == 0;
  }

  private static String activityDimensions(
      Instrumentation test, MainActivity activity, int expectedWidth, float expectedFont)
      throws Exception {
    String[] measured = {null};
    ui(
        test,
        () -> {
          var config = activity.getResources().getConfiguration();
          View decor = activity.getWindow().getDecorView();
          int widthPx = decor.getWidth();
          int widthDp = Math.round(widthPx / activity.getResources().getDisplayMetrics().density);
          if (expectedWidth != 0) {
            require(
                widthPx == GalleryUi.dp(activity, expectedWidth)
                    && ((View) get(activity, "root")).getWidth() == widthPx
                    && config.screenWidthDp == expectedWidth
                    && Math.abs(config.fontScale - expectedFont) < .001f,
                "capture profile matches rendered Activity bounds and configuration");
          }
          measured[0] =
              "widthDp="
                  + widthDp
                  + " widthPx="
                  + widthPx
                  + " configWidthDp="
                  + config.screenWidthDp
                  + " fontScale="
                  + config.fontScale;
        });
    return measured[0];
  }

  private static void waitFor(Instrumentation test, BooleanSupplier check, String label)
      throws Exception {
    long deadline = SystemClock.uptimeMillis() + FRAME_WAIT_MS;
    do {
      boolean[] ready = {false};
      ui(test, () -> ready[0] = check.getAsBoolean());
      if (ready[0]) return;
      Thread.sleep(20);
    } while (SystemClock.uptimeMillis() < deadline);
    throw new AssertionError(label);
  }

  private static void captureScreen(Instrumentation test, MainActivity activity, String name)
      throws Exception {
    captureScreen(test, activity, activity.getWindow().getDecorView(), () -> true, name);
  }

  private static void captureScreen(
      Instrumentation test, Context context, View decor, BooleanSupplier matches, String name)
      throws Exception {
    writeCapture(context, settledScreenshot(test, decor, matches), name);
  }

  private static Bitmap settledScreenshot(Instrumentation test, View decor, BooleanSupplier matches)
      throws Exception {
    // The render thread commits matching content, then a subsequent matching frame gives the
    // compositor a fresh frame before capture. A focused, visible window excludes outgoing sheets.
    for (int pass = 0; pass < 2; pass++) {
      Frame frame =
          new Frame(
              decor, () -> decor.isShown() && decor.hasWindowFocus() && matches.getAsBoolean());
      ui(test, frame::arm);
      try {
        require(frame.ready.await(FRAME_WAIT_MS, TimeUnit.MILLISECONDS), "matching capture frame");
      } finally {
        ui(test, frame::disarm);
      }
    }
    Bitmap image = test.getUiAutomation().takeScreenshot();
    require(image != null, "capture synthetic screen");
    return image;
  }

  private static void writeCapture(Context context, Bitmap image, String name) throws Exception {
    require(image != null, "capture synthetic screen");
    File folder = new File(context.getCacheDir(), "consistency-checks");
    try {
      require(folder.isDirectory() || folder.mkdirs(), "capture folder");
      try (FileOutputStream out = new FileOutputStream(new File(folder, name + ".png"))) {
        require(image.compress(Bitmap.CompressFormat.PNG, 100, out), "capture generated screen");
      }
    } finally {
      image.recycle();
    }
  }

  static void requireEmptyAvd(Instrumentation test) throws Exception {
    require("ranchu".equals(Build.HARDWARE), "disposable emulator hardware required");
    String name;
    try (ParcelFileDescriptor descriptor =
            test.getUiAutomation().executeShellCommand("getprop ro.boot.qemu.avd_name");
        FileInputStream input = new FileInputStream(descriptor.getFileDescriptor())) {
      byte[] output = new byte[128];
      int length = input.read(output);
      require(length > 0 && input.read() == -1, "unambiguous emulator name required");
      name = new String(output, 0, length, StandardCharsets.UTF_8).trim();
    }
    require(AVD.equals(name), "exact dedicated ranking AVD name required");
    requireEmptyData(test.getTargetContext());
  }

  private static void requireEmptyData(Context context) {
    require(
        context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
                == PackageManager.PERMISSION_GRANTED
            && context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO)
                == PackageManager.PERMISSION_GRANTED,
        "complete image and video fixture queries require permissions");
    require(
        context.getSharedPreferences("rankings", Context.MODE_PRIVATE).getAll().isEmpty(),
        "default ranking preferences must remain empty");
    for (Uri collection :
        List.of(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL))) {
      try (Cursor rows =
          context.getContentResolver().query(collection, new String[] {"_id"}, null, null, null)) {
        require(rows != null && rows.getCount() == 0, "MediaStore must remain empty");
      }
    }
  }

  private static void seedXml(SharedPreferences prefs, int entries) throws Exception {
    SharedPreferences.Editor editor = prefs.edit();
    for (int index = 0; index < entries / 2; index++) {
      editor.putString(
          "single-best-v1-" + PhotoRepository.digest("rank-fixture-single-" + index),
          index % 2 == 0 ? "0" : "1");
      editor.putString(
          "v1-" + PhotoRepository.digest("rank-fixture-a-" + index + "|rank-fixture-b-" + index),
          switch (index % 3) {
            case 0 -> "B:0";
            case 1 -> "A:2";
            default -> "1";
          });
    }
    require(editor.commit(), "seed isolated ranking XML");
  }

  static PhotoRanking readSavedBurst(SharedPreferences prefs, List<String> ids) throws Exception {
    List<String> sorted = new ArrayList<>(ids);
    sorted.sort(String::compareTo);
    String key = "v1-" + PhotoRepository.digest(String.join("|", sorted));
    return new PhotoRanking(sorted, prefs.getString(key, ""));
  }

  static boolean readSavedSingle(SharedPreferences prefs, String archiveKey) throws Exception {
    return "1"
        .equals(prefs.getString("single-best-v1-" + PhotoRepository.digest(archiveKey), null));
  }

  private static List<PhotoRepository.Photo> fixturePhotos(Context context, List<File> files)
      throws Exception {
    List<PhotoRepository.Photo> photos = new ArrayList<>();
    for (int index = 0; index < 4; index++) {
      File file = File.createTempFile("ranking-wait-", ".jpg", context.getCacheDir());
      files.add(file);
      Bitmap bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888);
      bitmap.eraseColor(0xff305060 + 0x120800 * index);
      try (FileOutputStream output = new FileOutputStream(file)) {
        require(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output), "write generated JPEG");
      } finally {
        bitmap.recycle();
      }
      photos.add(
          new PhotoRepository.Photo(
              Uri.fromFile(file),
              "合成写真" + index + ".jpg",
              "/ranking-wait-fixture",
              file.length(),
              1792298000L + index,
              1792298000000L + index * 1000));
    }
    return photos;
  }

  private static void installFixture(
      Instrumentation test,
      MainActivity activity,
      Map<String, Object> original,
      List<PhotoRepository.Photo> photos,
      RankingStore store)
      throws Exception {
    ui(
        test,
        () -> {
          for (String field :
              List.of(
                  "rankingStore",
                  "photos",
                  "bundles",
                  "burstMembers",
                  "burstPosition",
                  "rankFilter",
                  "selecting",
                  "albumFolder",
                  "ranking",
                  "rankingBasis")) {
            original.put(field, get(activity, field));
          }
          for (String field : List.of("rankCache", "rankingMembers", "photoBundles")) {
            original.put(field, new HashMap<>((Map<?, ?>) get(activity, field)));
          }
          set(activity, "rankingStore", store);
          set(activity, "photos", photos);
          set(activity, "bundles", List.of(List.of(0), BURST));
          set(activity, "burstMembers", null);
          set(activity, "rankFilter", 0);
          set(activity, "selecting", false);
          set(activity, "albumFolder", "/ranking-wait-fixture");
          ((Map<?, ?>) get(activity, "rankCache")).clear();
          call(activity, "gallery");
        });
    GalleryScreenChecks.settle(test, activity);
  }

  @SuppressWarnings("unchecked")
  private static void restoreAndFinish(
      Instrumentation test, MainActivity activity, Map<String, Object> original) throws Exception {
    try {
      ((ExecutorService) get(activity, "actions")).submit(() -> {}).get(5, TimeUnit.SECONDS);
    } finally {
      ui(
          test,
          () -> {
            try {
              original.forEach(
                  (field, value) -> {
                    if (value instanceof Map<?, ?> saved) {
                      Map<Object, Object> target = (Map<Object, Object>) get(activity, field);
                      target.clear();
                      target.putAll(saved);
                    } else set(activity, field, value);
                  });
            } finally {
              activity.finish();
            }
          });
      test.waitForIdleSync();
    }
  }

  private static String heldRead(
      Instrumentation test,
      MainActivity activity,
      SharedPreferences prefs,
      AtomicReference<Commit> next,
      Commit held,
      boolean baseline,
      PhotoRepository.Photo photo)
      throws Exception {
    ViewerScreenChecks.open(test, activity, 0);
    View decor = activity.getWindow().getDecorView();
    View startingRoot = (View) get(activity, "root");
    Frame oldFrame = new Frame(decor, () -> matchingStar(activity, startingRoot, false));
    Frame newFrame = new Frame(decor, () -> matchingStar(activity, startingRoot, true));
    String storeKey = "single-best-v1-" + PhotoRepository.digest(ArchiveStore.key(photo));
    require(!prefs.contains(storeKey), "fixture singleton starts without a saved row");
    next.set(held);
    ui(
        test,
        () -> {
          Button star = star(activity);
          require(star != null && !star.isSelected() && star.performClick(), "toggle real star");
          oldFrame.arm();
          newFrame.arm();
        });

    AtomicLong posted = new AtomicLong(), handled = new AtomicLong(), returned = new AtomicLong();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    CountDownLatch finished = new CountDownLatch(1);
    boolean blocked = false;
    try {
      require(held.entered.await(2, TimeUnit.SECONDS), "writer entered fixture commit");
      Method open = MainActivity.class.getDeclaredMethod("showPhoto", int.class);
      open.setAccessible(true);
      posted.set(now());
      new Handler(Looper.getMainLooper())
          .post(
              () -> {
                handled.set(now());
                try {
                  invoke(open, activity, new Object[] {0});
                  returned.set(now());
                } catch (Throwable error) {
                  failure.set(error);
                } finally {
                  finished.countDown();
                }
              });
      long deadline = SystemClock.uptimeMillis() + PROOF_MS;
      do {
        blocked |= mainWaitingInSingleBest();
        if (baseline ? blocked : oldFrame.ready.getCount() == 0) break;
        Thread.sleep(8);
      } while (SystemClock.uptimeMillis() < deadline);
      if (baseline) {
        require(
            blocked && finished.getCount() != 0 && oldFrame.ready.getCount() != 0,
            "old UI read must wait on the writer monitor");
      } else {
        require(
            !blocked && oldFrame.committed > 0 && finished.getCount() == 0,
            "old committed star frame must arrive while writer is held");
      }
      require(held.delegateStart == 0, "held proof precedes Android commit");
    } finally {
      held.release();
    }
    require(finished.await(5, TimeUnit.SECONDS), "viewer reopen returned after gate release");
    if (failure.get() != null) throw new AssertionError(failure.get());
    require(held.done.await(5, TimeUnit.SECONDS) && held.success, "held write committed");
    require(
        !held.timedOut && !held.onMain && held.delegateStart >= held.released,
        "held writer and Android commit segments are separate");
    require(
        newFrame.ready.await(FRAME_WAIT_MS, TimeUnit.MILLISECONDS), "updated star frame displayed");
    require(
        "1".equals(prefs.getString(storeKey, null))
            && newFrame.committed >= held.delegateEnd
            && (baseline ? returned.get() >= held.delegateEnd : oldFrame.committed < held.released),
        "viewer state agrees with read gate and persisted singleton");
    ui(
        test,
        () -> {
          oldFrame.disarm();
          newFrame.disarm();
        });
    return "heldRead blocked="
        + blocked
        + " oldFrameBeforeRelease="
        + (oldFrame.committed > 0 && oldFrame.committed < held.released)
        + " inputToHandledNs="
        + (handled.get() - posted.get())
        + " handledToReturnNs="
        + (returned.get() - handled.get())
        + " gateNs="
        + (held.released - held.commitEntry)
        + " delegateCommitNs="
        + (held.delegateEnd - held.delegateStart)
        + " releaseToNewPreDrawNs="
        + (newFrame.preDraw - held.released)
        + " inputToNewFrameNs="
        + (newFrame.committed - posted.get());
  }

  private static boolean mainWaitingInSingleBest() {
    Thread main = Looper.getMainLooper().getThread();
    if (main.getState() != Thread.State.BLOCKED) return false;
    for (StackTraceElement frame : main.getStackTrace()) {
      if (frame.getClassName().equals(RankingStore.class.getName())
          && frame.getMethodName().equals("singleBest")) return true;
    }
    return false;
  }

  private static Button star(MainActivity activity) {
    View root = (View) get(activity, "root");
    return root.findViewWithTag("viewer-action-1位");
  }

  private static boolean matchingStar(MainActivity activity, View previous, boolean chosen) {
    if (get(activity, "root") == previous) return false;
    Button star = star(activity);
    return star != null && star.isShown() && star.isEnabled() && star.isSelected() == chosen;
  }

  private static String choose(
      Instrumentation test,
      MainActivity activity,
      SharedPreferences prefs,
      AtomicReference<Commit> next,
      boolean baseline,
      List<PhotoRepository.Photo> photos)
      throws Exception {
    List<String> keys = new ArrayList<>();
    for (int member : BURST) keys.add(ArchiveStore.key(photos.get(member)));
    keys.sort(String::compareTo);
    String key = "v1-" + PhotoRepository.digest(String.join("|", keys));
    require(!prefs.contains(key), "first comparison starts with an absent persisted row");

    ui(
        test,
        () -> {
          set(activity, "burstMembers", BURST);
          set(activity, "burstPosition", 0);
          call(activity, "openRanking");
        });
    View decor = activity.getWindow().getDecorView();
    Frame first = new Frame(decor, () -> comparisonVisible(activity, "B:"));
    ui(test, first::arm);
    require(
        first.ready.await(FRAME_WAIT_MS, TimeUnit.MILLISECONDS), "first comparison frame ready");
    require(!prefs.contains(key), "displayed B: is not a persisted row");

    Commit commit = new Commit(false);
    AtomicLong handled = new AtomicLong();
    Frame result = new Frame(decor, () -> comparisonVisible(activity, "B:0"));
    int[] location = new int[2];
    next.set(commit);
    ui(
        test,
        () -> {
          Button button = choice(activity);
          require(
              button != null && button.isShown() && button.isEnabled(), "comparison action ready");
          button.getLocationOnScreen(location);
          location[0] += button.getWidth() / 2;
          location[1] += button.getHeight() / 2;
          button.setOnTouchListener(
              (view, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_UP) handled.set(now());
                return false;
              });
          result.arm();
        });
    long downTime = SystemClock.uptimeMillis();
    sendPointer(test, downTime, MotionEvent.ACTION_DOWN, location);
    long input = now();
    sendPointer(test, downTime, MotionEvent.ACTION_UP, location);

    require(
        commit.done.await(5, TimeUnit.SECONDS) && commit.success, "comparison commit succeeded");
    require(
        result.ready.await(FRAME_WAIT_MS, TimeUnit.MILLISECONDS), "next comparison frame ready");
    require(
        commit.onMain == baseline
            && handled.get() >= input
            && commit.delegateStart >= handled.get()
            && result.preDraw >= commit.delegateEnd
            && "B:0".equals(prefs.getString(key, null)),
        "comparison input, thread, durable history and next frame agree");
    return "comparison kind=chooseA commitOnMain="
        + commit.onMain
        + " inputToHandledNs="
        + (handled.get() - input)
        + " handledToCommitNs="
        + (commit.delegateStart - handled.get())
        + " delegateCommitNs="
        + (commit.delegateEnd - commit.delegateStart)
        + " postCommitToPreDrawNs="
        + (result.preDraw - commit.delegateEnd)
        + " inputToResultFrameNs="
        + (result.committed - input);
  }

  private static Button choice(MainActivity activity) {
    return TagDetailChecks.button((View) get(activity, "root"), "Aを選ぶ");
  }

  private static boolean comparisonVisible(MainActivity activity, String journal) {
    PhotoRanking ranking = (PhotoRanking) get(activity, "ranking");
    Button button = choice(activity);
    return ranking != null
        && journal.equals(ranking.journal)
        && button != null
        && button.isShown()
        && button.isEnabled();
  }

  private static void sendPointer(Instrumentation test, long down, int action, int[] location) {
    MotionEvent event =
        MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, location[0], location[1], 0);
    try {
      test.sendPointerSync(event);
    } finally {
      event.recycle();
    }
  }

  private static long now() {
    return SystemClock.elapsedRealtimeNanos();
  }

  private static final class Frame implements ViewTreeObserver.OnPreDrawListener {
    final View decor;
    final BooleanSupplier matches;
    final CountDownLatch ready = new CountDownLatch(1);
    volatile long preDraw, committed;

    Frame(View decor, BooleanSupplier matches) {
      this.decor = decor;
      this.matches = matches;
    }

    void arm() {
      require(decor.isHardwareAccelerated(), "hardware frame commit required");
      decor.getViewTreeObserver().addOnPreDrawListener(this);
      decor.invalidate();
    }

    void disarm() {
      decor.getViewTreeObserver().removeOnPreDrawListener(this);
    }

    @Override
    public boolean onPreDraw() {
      if (!matches.getAsBoolean()) return true;
      disarm();
      preDraw = now();
      decor
          .getViewTreeObserver()
          .registerFrameCommitCallback(
              () -> {
                committed = now();
                ready.countDown();
              });
      return true;
    }
  }

  static SharedPreferences observe(SharedPreferences actual, AtomicReference<Commit> next) {
    return (SharedPreferences)
        Proxy.newProxyInstance(
            SharedPreferences.class.getClassLoader(),
            new Class<?>[] {SharedPreferences.class},
            (proxy, method, args) -> {
              if (method.getName().equals("equals")) return proxy == args[0];
              if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
              Object value = invoke(method, actual, args);
              if (!method.getName().equals("edit")) return value;
              return Proxy.newProxyInstance(
                  SharedPreferences.Editor.class.getClassLoader(),
                  new Class<?>[] {SharedPreferences.Editor.class},
                  (editor, operation, values) -> {
                    if (operation.getName().equals("commit")) {
                      Commit commit = next.getAndSet(null);
                      if (commit != null) return commit.run((SharedPreferences.Editor) value);
                    }
                    Object result = invoke(operation, value, values);
                    return result == value ? editor : result;
                  });
            });
  }

  private static Object invoke(Method method, Object owner, Object[] args) throws Throwable {
    try {
      return method.invoke(owner, args);
    } catch (InvocationTargetException error) {
      throw error.getCause();
    }
  }

  static final class Commit {
    final CountDownLatch entered = new CountDownLatch(1), done = new CountDownLatch(1);
    private final CountDownLatch gate;
    private final boolean rejectCommitted;
    private final long gateLimitMillis;
    volatile long commitEntry, released, delegateStart, delegateEnd;
    volatile boolean onMain, success, timedOut;

    Commit(boolean held) {
      this(held, false);
    }

    Commit(boolean held, boolean rejectCommitted) {
      this(held, rejectCommitted, GATE_LIMIT_MS);
    }

    Commit(boolean held, boolean rejectCommitted, long gateLimitMillis) {
      this.rejectCommitted = rejectCommitted;
      this.gateLimitMillis = gateLimitMillis;
      gate = new CountDownLatch(held ? 1 : 0);
    }

    void release() {
      if (gate.getCount() != 0) {
        released = now();
        gate.countDown();
      }
    }

    boolean run(SharedPreferences.Editor actual) throws InterruptedException {
      onMain = Looper.myLooper() == Looper.getMainLooper();
      commitEntry = now();
      entered.countDown();
      try {
        if (!gate.await(gateLimitMillis, TimeUnit.MILLISECONDS)) {
          timedOut = true;
          return false;
        }
        delegateStart = now();
        try {
          success = actual.commit() && !rejectCommitted;
          return success;
        } finally {
          delegateEnd = now();
        }
      } finally {
        done.countDown();
      }
    }
  }
}
