package jp.nano.rawgallery;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.*;
import android.util.LruCache;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import jp.nano.rawgallery.camera.ImportProgress;
import jp.nano.rawgallery.gallery.LibraryScreen;

public final class MainActivity extends DiagnosticActivity {
  private static final int BG = GalleryUi.BG,
      PANEL = GalleryUi.PANEL,
      TEXT = GalleryUi.TEXT,
      MUTED = GalleryUi.MUTED,
      ACCENT = GalleryUi.ACCENT;
  private final ExecutorService scans = new DiagnosticExecutor("MainActivity.scans", 1),
      images = new DiagnosticExecutor("MainActivity.images", 2),
      actions = new DiagnosticExecutor("MainActivity.actions", 1);
  private final Handler main = new Handler(Looper.getMainLooper());
  private final LruCache<String, Bitmap> thumbs =
      new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        protected int sizeOf(String k, Bitmap b) {
          return b.getAllocationByteCount();
        }
      };
  private final ExecutorService historyReads = new DiagnosticExecutor("MainActivity.history", 1);
  private Map<String, List<ShareHistoryUi.Item>> shareHistory = Collections.emptyMap();
  private boolean historyLoaded,
      historyReadFailed,
      historyReading,
      historyReload,
      historyForeground,
      historyAnimating;
  private Runnable refreshShareBadges = () -> {}, refreshShareInfo = () -> {};
  private Runnable refreshSingleBest = () -> {};
  private Runnable refreshExpandedChoice = () -> {};
  private boolean expandedRankUnavailable;
  private AlertDialog shareHistoryFailure;
  private final SharedPreferences.OnSharedPreferenceChangeListener historyChanges =
      (prefs, key) -> {
        if ("revision".equals(key)) loadShareHistory();
        if ("failure_revision".equals(key)) showShareHistoryFailure();
        if ("write_failed".equals(key)
            && !prefs.getBoolean(key, false)
            && shareHistoryFailure != null) shareHistoryFailure.dismiss();
      };

  private List<ShareHistoryUi.Item> historyFor(PhotoRepository.Photo photo) {
    try {
      return shareHistory.getOrDefault(ArchiveStore.key(photo), Collections.emptyList());
    } catch (IOException unavailable) {
      return Collections.emptyList();
    }
  }

  private void loadShareHistory() {
    if (isDestroyed()) return;
    if (historyReading) {
      historyReload = true;
      return;
    }
    historyReading = true;
    historyReads.execute(
        () -> {
          Map<String, List<ShareHistoryUi.Item>> loaded = null;
          try {
            loaded = ShareHistoryUi.load(getApplicationContext());
          } catch (RuntimeException ignored) {
          }
          Map<String, List<ShareHistoryUi.Item>> result = loaded;
          main.post(
              () -> {
                historyReading = false;
                if (isDestroyed()) return;
                historyReadFailed = result == null;
                historyAnimating = historyLoaded && result != null;
                boolean added = false;
                if (historyAnimating)
                  for (String key : result.keySet())
                    if (ShareHistoryUi.added(
                        shareHistory.getOrDefault(key, Collections.emptyList()), result.get(key))) {
                      added = true;
                      break;
                    }
                if (result != null) {
                  shareHistory = result;
                  historyLoaded = true;
                }
                if (added && historyForeground)
                  Toast.makeText(this, "共有の記録を更新しました", Toast.LENGTH_LONG).show();
                if (grid != null && grid.isAttachedToWindow()) grid.invalidateViews();
                refreshShareBadges.run();
                refreshShareInfo.run();
                historyAnimating = false;
                if (historyReload) {
                  historyReload = false;
                  loadShareHistory();
                }
              });
        });
  }

  private void showShareHistoryFailure() {
    if (!historyForeground || isDestroyed() || shareHistoryFailure != null) return;
    SharedPreferences prefs = getSharedPreferences(ShareHistoryReceiver.PREFS, 0);
    if (!prefs.getBoolean("write_failed", false)) return;
    GalleryUi.Sheet sheet = new GalleryUi.Sheet(this);
    sheet
        .setTitle("共有の記録を保存できませんでした")
        .setMessage("前の記録は残っています。写真の共有はやり直しません。")
        .setNegativeButton(
            "閉じる",
            (d, w) -> {
              if (!ShareHistoryReceiver.canRetry()) prefs.edit().remove("write_failed").apply();
            });
    if (ShareHistoryReceiver.canRetry())
      sheet.setPositiveButton(
          "もう一度保存", (dialog, which) -> ShareHistoryReceiver.retryLater(getApplicationContext()));

    shareHistoryFailure = sheet.create();
    shareHistoryFailure.setOnDismissListener(dialog -> shareHistoryFailure = null);
    shareHistoryFailure.show();
  }

  private PhotoRepository repository;
  private static final int PHOTO_TILE_HEIGHT_DP = 96;
  private RankingStore rankingStore;
  private RankingStore watchedRankingStore;
  private RankingStore.Subscription rankingSubscription;
  private RankingStore.Snapshot observedRanks, cachedRanks, displayedRanks;
  private boolean rankingForeground;
  private Runnable refreshBurstRanks = () -> {};
  private StatusView rankEmpty;
  private LinearLayout rankFilterFailure;
  private boolean rankFilterReadFailed;
  private PhotoFilterSheet rankFilterSheet;
  private RankingStore.Burst rankingBasis;
  private boolean rankingBusy;
  private final List<View> rankingActions = new ArrayList<>();
  private TextView rankingSaveStatus;
  private SharePreparation sharePreparation;
  private ShareCapacityChecks shareCapacity;
  private ShareUi.Menu capacityMenu;
  private List<List<Integer>> bundles = new ArrayList<>();
  private int indexedBurstGapMillis;
  private List<Integer> burstMembers;
  private List<List<Integer>> displayed = new ArrayList<>();
  private List<PhotoRepository.Photo> photos = new ArrayList<>();
  private LinearLayout root, body;
  private TextView subtitle;
  private ScrollingGrid grid, albumGridView;
  private LinearLayout listHeader;
  private int albumScroll, albumTop;
  private boolean albumHeader = true, libraryAlbums = true;
  private String allPhotosFilter = "すべて";
  private int allPhotosRankFilter;
  private String shownGalleryKey;
  private final Map<String, GalleryPosition> galleryScroll = new HashMap<>();

  private void rememberGallery() {
    if (!albumHome
        && !detail
        && grid != null
        && grid.isAttachedToWindow()
        && shownGalleryKey != null
        && !photos.isEmpty()) {
      GalleryPosition position = GalleryPosition.capture(grid, photos, displayed);
      if (position != null) galleryScroll.put(shownGalleryKey, position);
    }
    if (!albumHome && !detail && albumFolder == null && burstMembers == null) {
      allPhotosFilter = filter;
      allPhotosRankFilter = rankFilter;
    }
  }

  private void rememberAlbums() {
    if (albumHome
        && albumGridView != null
        && albumGridView.isAttachedToWindow()
        && albumGridView.getChildCount() > 0) {
      albumScroll = albumGridView.firstCellPosition();
      albumTop = albumGridView.getChildAt(0).getTop();
      albumHeader = albumGridView.getFirstVisiblePosition() < albumGridView.getHeaderViewsCount();
    }
  }

  private void restoreGallery() {
    GalleryPosition position = galleryScroll.get(shownGalleryKey);
    if (position != null && !displayed.isEmpty()) position.restore(grid, photos, displayed);
    else grid.selectCell(0);
  }

  private void switchLibraryTab(boolean albums) {
    if (selecting) return;
    rememberGallery();
    rememberAlbums();
    DiagnosticLog.event("library.tab", 0, "albums", albums);
    libraryAlbums = albums;
    if (albums) albumHome();
    else {
      albumFolder = null;
      filter = allPhotosFilter;
      rankFilter = allPhotosRankFilter;
      burstMembers = null;
      rankCache.clear();
      photos = new ArrayList<>();
      bundles = new ArrayList<>();
      gallery();
      loadIndexedPhotos();
    }
  }

  private void returnLibraryRoot() {
    switchLibraryTab(libraryAlbums);
  }

  private boolean selecting, bestOnly = true, rankingFromSelection;
  private final Set<String> selectedPhotos = new LinkedHashSet<>();
  private SelectionResult presentedSelection;
  private int rankFilter;
  private final Map<Integer, List<Integer>> photoBundles = new HashMap<>();
  private final Map<List<Integer>, PhotoRanking> rankCache = new HashMap<>();
  private List<Integer> selectionBurst;
  private SelectionUi.Summary selectionCount;
  private StatusView selectionRankStatus;
  private TextView selectionShareNotice;
  private boolean selectionShareNeedsReview;
  private TextView selectionTitle;
  private Button selectionShare, selectionAll, selectionSort, selectionTag;
  private SelectedPhotoTags selectionTags;
  private BulkBestSheet.Entry selectionBulk;
  private TextView bulkSavedNotice;
  private GalleryAdapter adapter;
  private String filter = "すべて";
  private String albumFolder;
  private boolean albumHome = true, resumed;
  private final IndexRefreshRequests refreshRequests = new IndexRefreshRequests();
  private boolean pendingIndexUpdate;
  private long indexUpdateVersion;
  private final long startedAt = SystemClock.elapsedRealtime();
  private boolean reportedAlbums, reportedCover;
  private boolean mediaRefreshClosed;
  private final Runnable mediaChanged =
      () -> {
        if (!mediaRefreshClosed) refresh();
      };
  private final android.database.ContentObserver mediaObserver =
      new android.database.ContentObserver(main) {
        public void onChange(boolean selfChange) {
          if (mediaRefreshClosed) return;
          DiagnosticLog.event("media.changed", 0);
          // Bound the wait from the first notification even during a long import.
          if (!main.hasCallbacks(mediaChanged)) main.postDelayed(mediaChanged, 300);
        }
      };
  private volatile int generation = 0;
  private Future<?> viewing;
  private boolean detail = false;
  private boolean rankingScreen = false;
  private android.os.Parcelable rankingResultState;
  private String rankingResultFocus;
  private boolean rankingResultInputFocus, rankingResultAccessibilityFocus;
  private int rankingResultPosition, rankingResultOffset;
  private PhotoRanking ranking;
  private final Map<String, Integer> rankingMembers = new HashMap<>();
  private int index = 0;
  private PhotoRepository.Preview activePreview;
  private List<ShareTargets.Target> targets;
  private Future<?> sharing;
  private Dialog shareProgress;
  private int shareAttempt;
  private Bundle pendingRestore;
  private long burstRequest;
  private int burstPosition;

  private String generatedViewerUri;
  static final String GENERATED_IMAGE = "jp.nano.rawgallery.GENERATED_IMAGE";

  public void onCreate(Bundle state) {
    super.onCreate(state);
    indexedBurstGapMillis = savedBurstGapMillis();
    repository = new PhotoRepository(this);
    rankingStore = new RankingStore(this);
    sharePreparation = new SharePreparation(this, repository);
    shareCapacity =
        new ShareCapacityChecks(
            this,
            repository,
            () -> {
              if (isDestroyed()) return;
              if (selecting) updateSelection();
              if (capacityMenu != null && capacityMenu.isShowing())
                capacityMenu.capacity(shareCapacity.snapshot(capacityMenu.photos));
            });
    targets = ShareTargets.load(this);
    if (state != null) {
      libraryAlbums = state.getBoolean("libraryAlbums", state.getBoolean("home", true));
      allPhotosFilter = state.getString("allPhotosFilter", "すべて");
      allPhotosRankFilter = state.getInt("allPhotosRankFilter", 0);
    }
    if (state != null && !state.getBoolean("home", true)) {
      pendingRestore = state;
      albumFolder = state.getString("album");
      filter = state.getString("camera", "すべて");
      rankFilter = state.getInt("rankFilter", 0);
      gallery();
      loadIndexedPhotos();
    } else albumHome();
    getContentResolver()
        .registerContentObserver(
            android.provider.MediaStore.Files.getContentUri("external"), true, mediaObserver);
    GalleryIndexJob.schedule(this);
    String permission =
        Build.VERSION.SDK_INT >= 33
            ? Manifest.permission.READ_MEDIA_IMAGES
            : Manifest.permission.READ_EXTERNAL_STORAGE;
    if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
        || (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO)
                != PackageManager.PERMISSION_GRANTED)) requestPhotoAccess();
    openGeneratedImage(
        state == null
            ? getIntent().getStringExtra(GENERATED_IMAGE)
            : state.getString(GENERATED_IMAGE));
  }

  /** Only a published MediaStore image can enter the saved-result viewer. */
  private void openGeneratedImage(String value) {
    if (value == null) return;
    Uri uri = Uri.parse(value);
    String path = uri.getPath();
    if (!"content".equals(uri.getScheme())
        || !"media".equals(uri.getAuthority())
        || path == null
        || !path.matches("/(external|external_primary)/images/media/[0-9]+")) return;
    try (android.database.Cursor cursor =
        getContentResolver()
            .query(
                uri,
                new String[] {
                  android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                  android.provider.MediaStore.Images.Media.SIZE,
                  android.provider.MediaStore.Images.Media.DATE_MODIFIED,
                  android.provider.MediaStore.Images.Media.DATE_TAKEN,
                  android.provider.MediaStore.Images.Media.IS_PENDING,
                  android.provider.MediaStore.Images.Media.OWNER_PACKAGE_NAME
                },
                null,
                null,
                null)) {
      if (cursor == null
          || !cursor.moveToFirst()
          || cursor.getInt(4) != 0
          || !getPackageName().equals(cursor.getString(5))) return;
      generatedViewerUri = value;
      getIntent().removeExtra(GENERATED_IMAGE);
      albumHome = false;
      libraryAlbums = false;
      pendingRestore = null;
      burstMembers = null;
      photos =
          new ArrayList<>(
              java.util.List.of(
                  new PhotoRepository.Photo(
                      uri,
                      cursor.getString(0),
                      "Nano Banana",
                      cursor.getLong(1),
                      cursor.getLong(2),
                      cursor.getLong(3))));
      bundles = new ArrayList<>();
      showPhoto(0);
    } catch (RuntimeException error) {
      android.widget.Toast.makeText(this, "生成画像を開けませんでした", android.widget.Toast.LENGTH_SHORT)
          .show();
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    rememberGallery();
    rememberAlbums();
    if (detail
        && generatedViewerUri != null
        && index >= 0
        && index < photos.size()
        && generatedViewerUri.equals(photos.get(index).uri.toString()))
      state.putString(GENERATED_IMAGE, generatedViewerUri);
    state.putBoolean("libraryAlbums", libraryAlbums);
    state.putString("allPhotosFilter", allPhotosFilter);
    state.putInt("allPhotosRankFilter", allPhotosRankFilter);
    state.putBoolean("home", albumHome);
    state.putString("album", albumFolder);
    state.putString("camera", filter);
    state.putInt("rankFilter", rankFilter);
    state.putBoolean("selecting", selecting);
    state.putBoolean("bestOnly", bestOnly);
    state.putStringArrayList("selection", new ArrayList<>(selectedPhotos));
    List<Integer> scope = rankingFromSelection ? selectionBurst : burstMembers;
    if (scope != null) {
      ArrayList<String> members = new ArrayList<>();
      for (int member : scope) members.add(photos.get(member).uri.toString());
      state.putStringArrayList("burst", members);
    }
  }

  private int dp(float x) {
    return Math.round(x * getResources().getDisplayMetrics().density);
  }

  private TextView label(String s, int size, int color) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setFontFeatureSettings("kern");
    return t;
  }

  private Button button(String title, Runnable click) {
    return GalleryUi.button(this, title, click);
  }

  private void dock(Button... buttons) {
    root.addView(GalleryUi.dock(this, buttons));
  }

  private LinearLayout vertical() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private LinearLayout row() {
    LinearLayout l = new LinearLayout(this);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  private void base() {
    presentedSelection = null;
    refreshSingleBest = () -> {};
    refreshExpandedChoice = () -> {};
    expandedRankUnavailable = false;
    refreshBurstRanks = () -> {};
    rankEmpty = null;
    rankFilterFailure = null;
    rankingActions.clear();
    rankingSaveStatus = null;
    selectionShareNotice = null;
    refreshShareBadges = () -> {};
    refreshShareInfo = () -> {};
    root = vertical();
    cameraImportBand = null;
    libraryTabs = null;
    root.setBackgroundColor(BG);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          Insets i =
              insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
          v.setPadding(i.left, i.top, i.right, i.bottom);
          return insets;
        });
    setContentView(root);
    // Each rendered screen replaces this root; request the current OS insets again.
    root.requestApplyInsets();
  }

  private LibraryTabs libraryTabs;
  private boolean quickImportOpening;
  private boolean usbReceiverRegistered;
  private final BroadcastReceiver usbChanges =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          updateCameraTab();
        }
      };

  private void addLibraryTabs(boolean albums, boolean enabled) {
    libraryTabs =
        new LibraryTabs(
            this,
            albums,
            enabled,
            this::switchLibraryTab,
            () -> startActivity(CameraImportActivity.manualImportIntent(this, false)),
            this::startCameraImportFromTab);
    root.addView(libraryTabs);
    updateCameraTab();
  }

  private void updateCameraTab() {
    if (libraryTabs == null) return;
    var device = CameraImportActivity.connected(this);
    var state = ImportProgress.INSTANCE;
    libraryTabs.setCameraState(
        device != null,
        device == null ? null : device.getProductName(),
        state.getActive(),
        state.getDone(),
        state.getTotal(),
        state.getWaiting(),
        ImportProgressPresentation.current());
  }

  private void startCameraImportFromTab() {
    if (selecting || ImportProgress.INSTANCE.getActive() || quickImportOpening) return;
    quickImportOpening = true;
    startActivity(CameraImportActivity.manualImportIntent(this, true));
  }

  private TextView cameraImportBand;
  private final Runnable refreshCameraImportBand =
      () -> {
        updateCameraTab();
        TextView band = cameraImportBand;
        if (band == null) return;
        var state = jp.nano.rawgallery.camera.ImportProgress.INSTANCE;
        String text =
            state.getActive() || state.getLastMessage() != null
                ? ImportProgressPresentation.current().text()
                : null;
        band.setVisibility(text == null ? View.GONE : View.VISIBLE);
        if (!android.text.TextUtils.equals(band.getText(), text)) band.setText(text);
      };
  private final Runnable cameraImportChanged =
      () -> {
        main.removeCallbacks(refreshCameraImportBand);
        main.post(refreshCameraImportBand);
      };

  private void gallery() {
    watchRankings();
    DiagnosticLog.event("screen.gallery", burstRequest);
    rememberGallery();
    rememberAlbums();
    albumHome = false;
    if (viewing != null) viewing.cancel(true);
    detail = false;
    rankingScreen = false;
    generation++;
    activePreview = null;
    base();
    if (burstMembers != null && !selecting) {
      burstDetail();
      return;
    }
    refreshSingleBest = this::gallery;
    subtitle = label("", 14, MUTED);
    listStatus = new StatusView(this);
    String title =
        burstMembers != null ? "連写" : albumFolder == null ? "写真" : new File(albumFolder).getName();
    LinearLayout header;
    if (selecting) {
      selectionAll = LibraryUi.text(this, "表示分を選択", this::toggleVisibleSelection);
      header = LibraryUi.toolbar(this, "写真を選択", selectionAll);
      selectionTitle = (TextView) header.getChildAt(0);
      selectionTitle.setTextSize(20);
      header.addView(LibraryUi.icon(this, R.drawable.ic_close, "選択を終了", this::endSelection), 0);
    } else {
      header =
          LibraryUi.toolbar(
              this,
              title,
              LibraryUi.text(this, "選択", this::beginSelection),
              LibraryUi.icon(this, R.drawable.ic_filter, "絞り込み", this::chooseFilter),
              LibraryUi.icon(this, R.drawable.ic_settings, "設定とその他", this::libraryMenu));
      selectionTitle = null;
    }
    root.addView(header);
    if (!selecting) root.addView(LibraryUi.metadata(this, subtitle, listStatus));
    if (rankFilter != 0 || !filter.equals("すべて")) {
      String condition =
          (rankFilter == 0 ? "" : PhotoFilterSheet.RANKS[rankFilter])
              + (rankFilter != 0 && !filter.equals("すべて") ? " · " : "")
              + (filter.equals("すべて") ? "" : filter);
      if (selecting) {
        TextView applied = PhotoListUi.text(this, "絞り込み：" + condition, 13, 20, MUTED);
        applied.setTag("photo-filter-condition");
        applied.setPadding(dp(20), 0, dp(16), dp(8));
        applied.setContentDescription("適用中の絞り込み：" + condition + "。選択を終了すると変更できます");
        root.addView(applied);
      } else {
        Button clear = LibraryUi.text(this, condition + "  ×", () -> applyPhotoFilter("すべて", 0));
        clear.setContentDescription("絞り込みを解除：" + condition);
        clear.setTag("photo-filter-condition");
        root.addView(clear);
      }
    }
    if (!selecting && burstMembers == null) {
      cameraImportBand = label("", 14, TEXT);
      cameraImportBand.setPadding(dp(20), dp(8), dp(20), dp(8));
      cameraImportBand.setMinHeight(dp(48));
      cameraImportBand.setGravity(Gravity.CENTER_VERTICAL);
      cameraImportBand.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
      cameraImportBand.setOnClickListener(
          v -> startActivity(CameraImportActivity.showProgressIntent(this)));
      root.addView(cameraImportBand);
      refreshCameraImportBand.run();
    }
    collectListHeader();
    body = vertical();
    root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
    grid = new ScrollingGrid(this);
    grid.setVerticalScrollBarEnabled(false);
    grid.addHeaderView(listHeader, null, false);
    grid.setNumColumns(3);
    grid.setHorizontalSpacing(dp(2));
    grid.setVerticalSpacing(dp(2));
    grid.setPadding(0, 0, 0, 0);
    grid.setClipToPadding(false);
    displayed = new ArrayList<>();
    rebuildDisplayed();
    adapter = new GalleryAdapter();
    grid.setCells(adapter);
    shownGalleryKey =
        albumFolder + "|" + filter + "|" + rankFilter + "|" + String.valueOf(burstMembers);
    restoreGallery();
    grid.onCellClick(
        (p, v, pos, id) -> {
          if (!requireCurrentBurstGrouping()) return;
          if (selecting) {
            root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            toggleSelection(pos);
            return;
          }
          GalleryUi.feedback(v);
          DiagnosticLog.event("action.grid", 0, "position", pos);
          List<Integer> members = displayed.get(pos);
          if (members.size() > 1) {
            burstRequest = DiagnosticLog.id();
            DiagnosticLog.event("burst.open", burstRequest, "count", members.size());
            burstMembers = new ArrayList<>(members);
            burstPosition = 0;
            gallery();
          } else showPhoto(members.get(0));
        });
    // The grid is rebuilt by this listener, so emit before detaching it.
    grid.setHapticFeedbackEnabled(false);
    grid.onCellLongClick(
        (parent, view, pos, id) -> {
          if (photos.get(displayed.get(pos).get(0)).isVideo()) {
            feedback(StatusView.Symbol.PAUSED, "動画はタップして再生できます", null, "");
            return true;
          }
          if (!requireCurrentBurstGrouping()) return true;
          root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
          if (!selecting) {
            selecting = true;
            bestOnly = true;
            selectedPhotos.clear();
          }
          toggleSelection(pos);
          gallery();
          return true;
        });
    FrameLayout gridFrame = new FrameLayout(this);
    gridFrame.addView(grid, new FrameLayout.LayoutParams(-1, -1));
    rankEmpty = status(StatusView.Symbol.EMPTY, "", "表示する写真がありません");
    FrameLayout emptyState = new FrameLayout(this);
    emptyState.addView(rankEmpty, new FrameLayout.LayoutParams(-1, -1));
    updateRankEmpty(displayedRanks);
    gridFrame.addView(emptyState, new FrameLayout.LayoutParams(-1, -1));
    grid.setEmptyView(emptyState);
    body.addView(gridFrame, new LinearLayout.LayoutParams(-1, -1));
    int controlsStart = root.getChildCount();
    if (selecting) {
      selectionControls();
      SelectionUi.finishFooter(
          root,
          controlsStart,
          burstMembers == null
              ? null
              : () -> header.getMeasuredHeight() + dp(PHOTO_TILE_HEIGHT_DP));
    }
    if (albumFolder == null && burstMembers == null && !selecting) addLibraryTabs(false, true);
    if (selecting) updateSelection();
    else if (!photos.isEmpty()) updateCount();
    applyPendingIndexUpdate();
  }

  private void photoMenu() {
    DiagnosticLog.event("screen.photoMenu", 0);
    MoreMenuUi.show(
        this,
        "その他",
        new String[] {"カメラで絞り込み", "タグ", "設定とその他"},
        (d, which) -> {
          if (which == 0 && !selecting) chooseFilter();
          else if (which == 0)
            feedback(
                StatusView.Symbol.PAUSED, "選択を終了してからカメラを変更してください", this::endSelection, "選択を終了");
          else if (which == 1) openTags(null);
          else galleryMenu();
        });
  }

  private static final class ImageLoad {
    volatile boolean cancelled, complete;
  }

  private String imageKey(PhotoRepository.Photo photo, int edge) {
    return PreviewPlaceholders.key(photo) + ":" + edge;
  }

  private void previewInto(ImageView image, PhotoRepository.Photo photo, int size) {
    previewInto(image, photo, size, success -> {});
  }

  private void previewInto(
      ImageView image,
      PhotoRepository.Photo photo,
      int size,
      java.util.function.Consumer<Boolean> complete) {
    long request = DiagnosticLog.id();
    if (DiagnosticLog.currentRecorder() != null) {
      DiagnosticLog.event(
          "image.request",
          request,
          "burst",
          burstRequest,
          "photo",
          DiagnosticLog.photo(photo),
          "size",
          size,
          "format",
          photo.isVideo() ? "VIDEO" : photo.isJpeg() ? "JPEG" : "RAW");
    }
    String key = imageKey(photo, size);
    if (image.getTag() instanceof ImageLoad) ((ImageLoad) image.getTag()).cancelled = true;
    ImageLoad load = new ImageLoad();
    image.setTag(load);
    if (image instanceof DiagnosticImageView) ((DiagnosticImageView) image).loading();
    image.setImageDrawable(null);
    int token = generation;
    java.util.function.BooleanSupplier cancelled = () -> load.cancelled || token != generation;
    Bitmap cached = thumbs.get(key);
    DiagnosticLog.event("image.memory", request, "hit", cached != null);
    if (cached != null) {
      load.complete = true;
      if (image instanceof DiagnosticImageView) ((DiagnosticImageView) image).bound(request);
      image.setImageBitmap(cached);
      DiagnosticLog.event("image.bind", request);
      complete.accept(true);
      return;
    }
    Bitmap provisional = size > 600 ? thumbs.get(imageKey(photo, 400)) : null;
    if (provisional == null && size > 600) provisional = thumbs.get(imageKey(photo, 600));
    if (provisional != null) {
      image.setImageBitmap(provisional);
      DiagnosticLog.event("image.provisional", request, "kind", "thumbnail");
    } else if (size >= 400 && size <= 600) {
      PreviewPlaceholders.get(this)
          .load(
              photo,
              () -> !cancelled.getAsBoolean() && !load.complete,
              bitmap -> {
                image.setImageBitmap(bitmap);
                image.setStateDescription("読み込み中");
                DiagnosticLog.event("image.placeholder", request);
              });
    }
    long previous = DiagnosticLog.use(request);
    images.execute(
        () -> {
          if (cancelled.getAsBoolean()) {
            DiagnosticLog.event("image.discard", request);
            return;
          }
          try {
            Bitmap bitmap =
                size <= 600 || photo.isVideo()
                    ? repository.thumbnail(photo, size, cancelled)
                    : PhotoRepository.bitmap(repository.preview(photo, cancelled), size);
            if (cancelled.getAsBoolean()) {
              bitmap.recycle();
              DiagnosticLog.event("image.discard", request);
              return;
            }
            thumbs.put(key, bitmap);
            DiagnosticLog.event("image.post", request);
            main.post(
                () -> {
                  if (!isDestroyed() && !cancelled.getAsBoolean() && image.getTag() == load) {
                    load.complete = true;
                    if (image instanceof DiagnosticImageView)
                      ((DiagnosticImageView) image).bound(request);
                    image.setImageBitmap(bitmap);
                    image.setStateDescription("表示済み");
                    DiagnosticLog.event("image.bind", request);
                    complete.accept(true);
                  } else DiagnosticLog.event("image.discard", request);
                });
            PreviewPlaceholders.get(this).remember(photo, bitmap);
          } catch (Exception e) {
            if (cancelled.getAsBoolean()) {
              DiagnosticLog.event("image.discard", request);
              return;
            }
            DiagnosticLog.event("image.fail", request, "error", e.getClass().getSimpleName());
            main.post(
                () -> {
                  if (!isDestroyed() && !cancelled.getAsBoolean() && image.getTag() == load) {
                    load.complete = true;
                    image.setStateDescription("読み込めませんでした");
                    image.setContentDescription(photo.name + "、プレビューを読み込めませんでした");
                    DiagnosticLog.event("image.failure_applied", request);
                    complete.accept(false);
                  } else DiagnosticLog.event("image.discard", request);
                });
          }
        });
    DiagnosticLog.use(previous);
  }

  private String burstSavedNotice;

  private void burstDetail() {
    DiagnosticLog.event("screen.burstDetail", burstRequest);
    grid = null;
    burstPosition = Math.max(0, Math.min(burstPosition, burstMembers.size() - 1));
    LinearLayout header = row();
    header.setPadding(dp(20), dp(40), dp(20), dp(16));

    LinearLayout heading = vertical();
    TextView title = label("連写 " + burstMembers.size() + "枚", 20, TEXT);
    title.setAccessibilityHeading(true);
    title.setIncludeFontPadding(false);
    title.setTypeface(
        android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
    title.setMinHeight(dp(28));
    heading.addView(title);
    subtitle = label("", 12, MUTED);
    header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));

    FrameLayout stage = new FrameLayout(this);
    stage.setBackgroundColor(Color.BLACK);
    ImageView hero = new DiagnosticImageView(this);
    hero.setScaleType(ImageView.ScaleType.FIT_CENTER);
    stage.addView(hero, new FrameLayout.LayoutParams(-1, -1));
    ShareHistoryUi.Overlay sharedHero =
        new ShareHistoryUi.Overlay(
            this,
            () -> {
              android.graphics.drawable.Drawable drawable = hero.getDrawable();
              if (drawable == null
                  || drawable.getIntrinsicWidth() <= 0
                  || drawable.getIntrinsicHeight() <= 0)
                return new RectF(0, 0, stage.getWidth(), stage.getHeight());
              RectF bounds =
                  new RectF(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
              hero.getImageMatrix().mapRect(bounds);
              bounds.offset(
                  hero.getLeft() + hero.getPaddingLeft(), hero.getTop() + hero.getPaddingTop());
              return bounds;
            },
            42,
            3,
            false);
    stage.addView(sharedHero, new FrameLayout.LayoutParams(-1, -1));
    TextView rank = label("", 12, Color.WHITE);
    rank.setGravity(Gravity.CENTER);
    rank.setIncludeFontPadding(false);
    rank.setPadding(dp(10), 0, dp(10), 0);
    TextView rankState = label("", 13, MUTED);
    rankState.setVisibility(View.GONE);
    heading.addView(rankState);
    FrameLayout.LayoutParams badge =
        new FrameLayout.LayoutParams(-2, dp(28), Gravity.TOP | Gravity.START);
    badge.setMargins(dp(12), dp(12), dp(12), 0);
    stage.addView(rank, badge);
    TextView notice = label("", 14, TEXT);
    notice.setMinHeight(dp(48));
    notice.setGravity(Gravity.CENTER_VERTICAL);
    notice.setPadding(dp(14), dp(8), dp(14), dp(8));
    android.graphics.drawable.GradientDrawable noticeBackground =
        GalleryUi.shape(this, 0xf01c1c1f, 24);
    noticeBackground.setStroke(dp(1), 0xff3a3a3f);
    notice.setBackground(noticeBackground);
    notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    notice.setVisibility(View.GONE);
    FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
    np.setMargins(dp(16), dp(48), dp(16), 0);
    stage.addView(notice, np);
    hero.setOnClickListener(v -> showPhoto(burstMembers.get(burstPosition)));
    HorizontalScrollView stripScroll = new HorizontalScrollView(this);
    stripScroll.setHorizontalScrollBarEnabled(false);
    stripScroll.setFillViewport(true);
    LinearLayout strip = row();
    strip.setGravity(Gravity.CENTER);
    strip.setPadding(0, 0, 0, 0);
    List<PhotoRepository.Photo> members = new ArrayList<>();
    for (int member : burstMembers) members.add(photos.get(member));
    int screenGeneration = generation;
    StatusView loading = new StatusView(this);
    loading.collapseWhenIdle();
    loading.asCompact();
    heading.addView(loading);
    Button retry = button("再試行", () -> {});
    final Runnable[] selection = new Runnable[1];
    BurstPreviews previews =
        new BurstPreviews(
            this,
            hero,
            stage,
            strip,
            loading,
            retry,
            members,
            (image, photo, edge, complete) ->
                previewInto(
                    image,
                    photo,
                    edge,
                    loaded -> {
                      complete.accept(loaded);
                      sharedHero.postInvalidateOnAnimation();
                    }),
            () -> screenGeneration == generation && !isDestroyed(),
            position -> {
              burstPosition = position;
              selection[0].run();
            });
    refreshShareBadges =
        () -> {
          if (burstMembers == null || burstPosition >= burstMembers.size()) return;
          PhotoRepository.Photo current = photos.get(burstMembers.get(burstPosition));
          sharedHero.items(historyFor(current), historyAnimating);
          hero.setContentDescription(
              current.name + "、タップで写真を拡大" + ShareHistoryUi.description(historyFor(current)));
          previews.history(this::historyFor);
        };
    Runnable update =
        () -> {
          DiagnosticLog.event("burst.select", burstRequest, "position", burstPosition);
          int member = burstMembers.get(burstPosition);
          PhotoRepository.Photo photo = photos.get(member);
          notice.setVisibility(View.GONE);
          previews.select(burstPosition);
          refreshShareBadges.run();
          int bestPosition = 0;
          String winners = "";
          boolean tied = false;
          String rankNumber = "", rankDescription = "順位が未決定";
          StatusView.Symbol rankSymbol = StatusView.Symbol.UNKNOWN;
          RankingStore.Snapshot snapshot = rankingStore.snapshot();
          try {
            PhotoRanking saved = rankingFor(snapshot, burstMembers);
            if (saved.done()) {
              List<String> positions = new ArrayList<>();
              int[] ranks = new int[burstMembers.size()];
              for (int i = 0; i < burstMembers.size(); i++) {
                ranks[i] = saved.rankOf(ArchiveStore.key(photos.get(burstMembers.get(i))));
                if (ranks[i] == 1) positions.add(Integer.toString(i + 1));
              }
              winners = String.join("・", positions);
              bestPosition = Integer.parseInt(positions.get(0));
              tied = positions.size() > 1;
              previews.ranks(ranks);
              int number = saved.rankOf(ArchiveStore.key(photo));
              rankNumber = (number == 1 && tied ? "同率" : "") + number + "位";
              rankDescription = rankNumber + "、順位決定済み";
              rankSymbol = StatusView.Symbol.COMPLETE;
            } else if (saved.decisions() > 0) {
              rankDescription = "順位決めの途中";
              rankSymbol = StatusView.Symbol.PAUSED;
            }
          } catch (Exception e) {
            boolean pending = snapshot.phase() == RankingStore.Phase.PENDING;
            rankDescription = pending ? "順位を読み込んでいます" : "順位を読み込めません";
            rankSymbol = pending ? StatusView.Symbol.RUNNING : StatusView.Symbol.FAILED;
          }
          if (rankSymbol != StatusView.Symbol.COMPLETE)
            previews.ranks(new int[burstMembers.size()]);
          rank.setVisibility(rankSymbol == StatusView.Symbol.COMPLETE ? View.VISIBLE : View.GONE);
          rank.setText(rankNumber);
          rank.setContentDescription(rankDescription);
          boolean winner = "1位".equals(rankNumber) || "同率1位".equals(rankNumber);
          rank.setTextColor(winner ? 0xff241800 : Color.WHITE);
          rank.setBackground(GalleryUi.shape(this, winner ? 0xfff5b947 : 0xa8000000, 14));
          rankState.setVisibility(
              rankSymbol == StatusView.Symbol.UNKNOWN ? View.GONE : View.VISIBLE);
          RankingUi.state(rankState, rankSymbol, bestPosition);
          if (rankSymbol == StatusView.Symbol.COMPLETE) rankState.setText(winners + "枚目");
          else if (rankSymbol == StatusView.Symbol.RUNNING
              || rankSymbol == StatusView.Symbol.FAILED) rankState.setText(rankDescription);
          rankState.setContentDescription(
              rankSymbol == StatusView.Symbol.COMPLETE
                  ? "順位決定済み、1位は" + winners + "枚目" + (tied ? "、同率" : "")
                  : rankDescription);
          heading.setContentDescription("連写 " + burstMembers.size() + "枚、" + rankDescription);
        };
    selection[0] = update;
    stripScroll.addView(strip);
    LinearLayout controls = vertical();
    controls.setPadding(dp(16), dp(8), dp(16), dp(20));
    controls.addView(stripScroll);
    controls.addView(retry);
    Button sort = GalleryUi.neutralPrimary(button(sortLabel(), this::openRanking));
    sort.setElevation(0);
    sort.setStateListAnimator(null);
    RankingUi.add(controls, sort, 8);
    Button best =
        RankingUi.action(button("この写真を1位に", this::chooseCurrentBest), false, 48, 14, null);
    RankingUi.inline(best, RankingUi.star(this), "1位にする", 18);
    best.setTooltipText(best.getContentDescription());
    Button share =
        RankingUi.action(
            button(
                "共有",
                () -> {
                  List<PhotoRepository.Photo> selected = new ArrayList<>();
                  for (int member : burstMembers) selected.add(photos.get(member));
                  shareMenu(selected);
                }),
            false,
            48,
            14,
            "共有");
    RankingUi.add(controls, RankingUi.pair(this, best, share), 8);
    refreshBurstRanks =
        () -> {
          update.run();
          String currentSortLabel = sortLabel();
          sort.setText(currentSortLabel);
          sort.setContentDescription(currentSortLabel);
          boolean pending = rankingStore.snapshot().phase() == RankingStore.Phase.PENDING;
          sort.setEnabled(!pending);
          best.setEnabled(!pending);
        };
    RankingUi.addDock(
        controls,
        RankingUi.dock(
            this,
            button(
                "前の写真",
                () -> {
                  burstPosition = (burstPosition + burstMembers.size() - 1) % burstMembers.size();
                  update.run();
                }),
            button(
                "次の写真",
                () -> {
                  burstPosition = (burstPosition + 1) % burstMembers.size();
                  update.run();
                }),
            button("拡大", () -> showPhoto(burstMembers.get(burstPosition))),
            button(
                "選択",
                () -> {
                  selecting = true;
                  bestOnly = true;
                  gallery();
                }),
            button("その他", this::photoMenu)),
        8);
    root.addView(
        new RankingUi.Screen(this, header, stage, controls, true),
        new LinearLayout.LayoutParams(-1, 0, 1));
    refreshBurstRanks.run();
    if (burstSavedNotice != null) {
      notice.setText("✓  " + burstSavedNotice);
      burstSavedNotice = null;
      notice.setVisibility(View.VISIBLE);
      notice.postDelayed(() -> notice.setVisibility(View.GONE), 4000);
    }
  }

  private void chooseCurrentBest() {
    RankingStore.Snapshot snapshot = rankingStore.snapshot();
    try {
      RankingStore.Burst basis = rankingBasis(snapshot, burstMembers);
      PhotoRanking saved = basis.ranking();
      List<Integer> members = List.copyOf(burstMembers);
      String current = ArchiveStore.key(photos.get(members.get(burstPosition)));
      int position = burstPosition, screen = generation;
      List<String> winners = new ArrayList<>();
      if (saved.done())
        for (int i = 0; i < members.size(); i++)
          if (saved.best().contains(ArchiveStore.key(photos.get(members.get(i)))))
            winners.add((i + 1) + "枚目");
      boolean canAdd = saved.done() && !saved.best().contains(current);
      boolean tiedWinner =
          saved.done() && saved.best().contains(current) && saved.best().size() > 1;
      RankingStore store = rankingStore;
      BurstBestSheet[] sheet = new BurstBestSheet[1];
      sheet[0] =
          new BurstBestSheet(
              this,
              canAdd,
              tiedWinner,
              (position + 1) + "枚目。いまの1位：" + String.join("・", winners),
              (together, done) -> {
                long request = DiagnosticLog.id();
                DiagnosticLog.event(
                    "ranking.direct.start",
                    request,
                    "together",
                    together ? 1 : 0,
                    "count",
                    saved.ids.size());
                store
                    .submitBurst(
                        basis,
                        () -> {
                          if (together) return basis.ranking().addCoWinner(current);
                          PhotoRanking next = new PhotoRanking(basis.ids(), "B:");
                          while (!next.done()) next = next.choose(next.upper().equals(current));
                          return next;
                        })
                    .whenCompleteAsync(
                        (result, error) -> {
                          boolean persisted = rankingAccepted(result, error);
                          DiagnosticLog.event(
                              persisted ? "ranking.direct.saved" : "ranking.direct.failed",
                              request);
                          if (isDestroyed() || isFinishing() || store != rankingStore) return;
                          if (rankingReadConflict(result)) {
                            sheet[0].dismiss();
                            if (generation == screen) rankChangedFeedback();
                            return;
                          }
                          done.accept(persisted);
                          if (persisted
                              && generation == screen
                              && members.equals(burstMembers)
                              && burstPosition == position) {
                            burstSavedNotice = together ? "同率1位に追加しました" : "1位にしました";
                            gallery();
                            rankingFrame(root, "ranking.direct.frame", request);
                          }
                        },
                        main::post);
              });
      sheet[0].show();
    } catch (Exception e) {
      reportUnreadableRanking(snapshot, this::chooseCurrentBest);
    }
  }

  private static boolean rankingAccepted(RankingStore.WriteResult result, Throwable error) {
    return error == null
        && result != null
        && (result.outcome() == RankingStore.Outcome.SAVED
            || result.outcome() == RankingStore.Outcome.UNCHANGED);
  }

  private static boolean rankingReadConflict(RankingStore.WriteResult result) {
    return result != null
        && (result.outcome() == RankingStore.Outcome.CONFLICT
            || result.outcome() == RankingStore.Outcome.READ_FAILED);
  }

  private void rankChangedFeedback() {
    feedback(StatusView.Symbol.PAUSED, "順位が変わりました。確認してからもう一度操作してください", null, "");
  }

  private static void rankingFrame(View view, String event, long request) {
    view.getViewTreeObserver()
        .addOnPreDrawListener(
            new ViewTreeObserver.OnPreDrawListener() {
              @Override
              public boolean onPreDraw() {
                view.getViewTreeObserver().removeOnPreDrawListener(this);
                if (view.isHardwareAccelerated())
                  view.getViewTreeObserver()
                      .registerFrameCommitCallback(() -> DiagnosticLog.event(event, request));
                return true;
              }
            });
    view.invalidate();
  }

  private PhotoRanking rankingFor(List<Integer> members) throws IOException {
    return rankingFor(rankingStore.snapshot(), members);
  }

  private PhotoRanking rankingFor(RankingStore.Snapshot snapshot, List<Integer> members)
      throws IOException {
    if (snapshot != cachedRanks) {
      rankCache.clear();
      cachedRanks = snapshot;
    }
    PhotoRanking cached = rankCache.get(members);
    if (cached != null) return cached;
    PhotoRanking saved = rankingBasis(snapshot, members).ranking();
    rankCache.put(List.copyOf(members), saved);
    return saved;
  }

  private RankingStore.Burst rankingBasis(RankingStore.Snapshot snapshot, List<Integer> members)
      throws IOException {
    List<String> ids = new ArrayList<>();
    for (int i : members) ids.add(ArchiveStore.key(photos.get(i)));
    return snapshot.burst(ids);
  }

  private void watchRankings() {
    if (!rankingForeground || watchedRankingStore == rankingStore || rankingStore == null) return;
    stopWatchingRankings();
    RankingStore owner = rankingStore;
    watchedRankingStore = owner;
    rankingSubscription = owner.observe(main::post, () -> rankingsPublished(owner));
  }

  private void stopWatchingRankings() {
    if (rankingSubscription != null) rankingSubscription.close();
    rankingSubscription = null;
    watchedRankingStore = null;
  }

  private void rankingsPublished(RankingStore owner) {
    if (!rankingForeground
        || isDestroyed()
        || owner != watchedRankingStore
        || owner != rankingStore) return;
    RankingStore.Snapshot snapshot = owner.snapshot();
    if (snapshot == observedRanks) return;
    observedRanks = snapshot;
    rankCache.clear();
    presentedSelection = null;
    if (rankFilterSheet != null && rankFilterSheet.isShowing()) rankFilterSheet.refreshCount();
    if (rankingScreen) refreshComparison(snapshot);
    else if (detail && returnToRanking) refreshExpandedComparison(snapshot, false);
    else if (detail) refreshSingleBest.run();
    else if (!albumHome) {
      if (burstMembers != null && !selecting) refreshBurstRanks.run();
      else if (grid != null && adapter != null) {
        GalleryPosition position = GalleryPosition.capture(grid, photos, displayed);
        rebuildDisplayed();
        updateRankEmpty(displayedRanks);
        adapter.notifyDataSetChanged();
        if (position != null && !displayed.isEmpty()) position.restore(grid, photos, displayed);
        if (selecting) updateSelection();
        else if (!photos.isEmpty()) updateCount();
      }
    }
  }

  private void reportUnreadableRanking(RankingStore.Snapshot snapshot, Runnable retry) {
    if (snapshot.phase() == RankingStore.Phase.PENDING) {
      feedback(StatusView.Symbol.RUNNING, "順位を読み込んでいます", null, "");
    } else {
      feedback(
          StatusView.Symbol.FAILED,
          "順位を読み込めませんでした",
          () -> {
            rankingStore.retryLoad();
            if (rankingStore.snapshot().phase() == RankingStore.Phase.READY) retry.run();
          },
          "再試行");
    }
  }

  private void updateRankEmpty(RankingStore.Snapshot snapshot) {
    if (rankEmpty == null) return;
    if (rankFilterFailure != null) rankFilterFailure.setVisibility(View.GONE);
    if (rankFilter != 0 && snapshot.phase() == RankingStore.Phase.PENDING) {
      rankEmpty.begin("順位を読み込んでいます");
    } else if (rankFilter != 0
        && (snapshot.phase() == RankingStore.Phase.FAILED || rankFilterReadFailed)) {
      rankEmpty.clear();
      if (rankFilterFailure == null) {
        rankFilterFailure = vertical();
        rankFilterFailure.setGravity(Gravity.CENTER_HORIZONTAL);
        rankFilterFailure.setPadding(dp(24), 0, dp(24), 0);
        TextView message = label("順位を読み込めませんでした", 14, TEXT);
        message.setGravity(Gravity.CENTER);
        message.setTag("catalog-ranking-failure");
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        rankFilterFailure.addView(message, new LinearLayout.LayoutParams(-1, -2));
        Button retry =
            LibraryUi.text(
                this,
                "順位を読み直す",
                () -> {
                  rankingStore.retryLoad();
                  if (rankingStore.snapshot().phase() == RankingStore.Phase.READY) gallery();
                });
        retry.setTag("catalog-ranking-retry");
        rankFilterFailure.addView(retry, new LinearLayout.LayoutParams(-2, -2));
        ((FrameLayout) rankEmpty.getParent())
            .addView(rankFilterFailure, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
      }
      rankFilterFailure.setVisibility(View.VISIBLE);
    } else rankEmpty.show(StatusView.Symbol.EMPTY, "", "表示する写真がありません", null, "");
  }

  private final class SelectionResult {
    final List<PhotoRepository.Photo> galleryPhotos = MainActivity.this.photos;
    final List<List<Integer>> galleryBundles = bundles;
    final Set<String> chosen = Set.copyOf(selectedPhotos);
    final boolean winnersOnly = bestOnly;
    final RankingStore.Snapshot ranks = rankingStore.snapshot();
    final List<PhotoRepository.Photo> photos = new ArrayList<>();
    final List<List<Integer>> unresolved = new ArrayList<>();
    boolean retryRanking;

    boolean matchesSelection() {
      return galleryPhotos == MainActivity.this.photos
          && galleryBundles == bundles
          && winnersOnly == bestOnly
          && chosen.equals(selectedPhotos);
    }
  }

  private SelectionResult selectionResult() {
    if (presentedSelection == null
        || !presentedSelection.matchesSelection()
        || presentedSelection.ranks != rankingStore.snapshot())
      presentedSelection = resolveSelection();
    return presentedSelection;
  }

  private boolean isCurrentSelection(SelectionResult result) {
    if (!result.matchesSelection() || result.retryRanking) return false;
    if (result.ranks == rankingStore.snapshot()) return true;
    SelectionResult current = selectionResult();
    return !current.retryRanking
        && current.photos.equals(result.photos)
        && current.unresolved.equals(result.unresolved)
        && current.ranks == rankingStore.snapshot();
  }

  private void shareSelectionChanged() {
    if (!selecting || selectionShareNotice == null) return;
    showShareSelectionNotice(true);
    updateSelection();
  }

  private void showShareSelectionNotice(boolean changed) {
    selectionShareNeedsReview = changed;
    if (selectionShareNotice != null) {
      selectionShareNotice.setVisibility(changed ? View.VISIBLE : View.GONE);
    }
  }

  private SelectionResult resolveSelection() {
    SelectionResult result = new SelectionResult();
    Set<Integer> requested = new LinkedHashSet<>();
    for (int i = 0; i < photos.size(); i++)
      if (!photos.get(i).isVideo() && selectedPhotos.contains(photos.get(i).uri.toString()))
        requested.add(i);
    BestShotSelection resolved =
        BestShotSelection.resolve(
            requested,
            bundles,
            bestOnly,
            group -> {
              try {
                PhotoRanking r = rankingFor(result.ranks, group);
                if (!r.done()) return null;
                Set<String> best = new HashSet<>(r.best());
                Set<Integer> winners = new HashSet<>();
                for (int member : group)
                  if (best.contains(ArchiveStore.key(photos.get(member)))) winners.add(member);
                return winners;
              } catch (IllegalArgumentException | IOException e) {
                result.retryRanking = true;
                return null;
              }
            });
    for (int member : resolved.members) result.photos.add(photos.get(member));
    result.unresolved.addAll(resolved.unresolved);
    return result;
  }

  private boolean tileSelected(List<Integer> members) {
    for (int member : members)
      if (!selectedPhotos.contains(photos.get(member).uri.toString())) return false;
    return !members.isEmpty();
  }

  private void toggleSelection(int pos) {
    showShareSelectionNotice(false);
    List<Integer> members = displayed.get(pos);
    if (photos.get(members.get(0)).isVideo()) {
      feedback(StatusView.Symbol.PAUSED, "動画は写真の選択対象外です", null, "");
      return;
    }
    boolean remove = tileSelected(members);
    for (int member : members) {
      String key = photos.get(member).uri.toString();
      if (remove) selectedPhotos.remove(key);
      else selectedPhotos.add(key);
    }
    adapter.notifyDataSetChanged();
    updateSelection();
  }

  private boolean allVisibleSelected() {
    if (displayed.isEmpty()) return false;
    boolean hasPhotos = false;
    for (List<Integer> members : displayed) {
      if (photos.get(members.get(0)).isVideo()) continue;
      hasPhotos = true;
      if (!tileSelected(members)) return false;
    }
    return hasPhotos;
  }

  private String sortLabel() {
    try {
      PhotoRanking saved = rankingFor(burstMembers);
      return saved.done() ? "ソート結果" : saved.decisions() > 0 ? "ソートを再開" : "ソート";
    } catch (Exception e) {
      return "ソート";
    }
  }

  private void rebuildDisplayed() {
    photoBundles.clear();
    for (List<Integer> group : bundles) for (int member : group) photoBundles.put(member, group);
    displayedRanks = rankingStore.snapshot();
    rankFilterReadFailed = false;
    if (rankFilter != 0 && displayedRanks.phase() != RankingStore.Phase.READY) {
      displayed = new ArrayList<>();
      return;
    }
    RankingStore.Snapshot snapshot = displayedRanks;
    try {
      displayed =
          RankedPhotoGroups.filter(
              photos,
              burstMembers == null ? bundles : List.of(burstMembers),
              burstMembers != null,
              rankFilter,
              snapshot,
              members -> rankingFor(snapshot, members));
    } catch (IOException | IllegalArgumentException unreadable) {
      rankFilterReadFailed = true;
      displayed = new ArrayList<>();
    }
  }

  private void beginSelection() {
    if (photos.isEmpty() || !requireCurrentBurstGrouping()) return;
    showShareSelectionNotice(false);
    selecting = true;
    bestOnly = true;
    selectedPhotos.clear();
    gallery();
  }

  private int savedBurstGapMillis() {
    return BurstGapSettings.readMillis(SettingsRepository.open(this));
  }

  private boolean requireCurrentBurstGrouping() {
    if (indexedBurstGapMillis == savedBurstGapMillis()) return true;
    listStatus.begin("連写のまとめ方を更新しています");
    refreshIndex(false);
    return false;
  }

  private void tagSelectedPhotos() {
    LinkedHashMap<String, PhotoRepository.Photo> chosen = new LinkedHashMap<>();
    for (PhotoRepository.Photo photo : photos)
      if (selectedPhotos.contains(photo.uri.toString()) && !photo.isVideo())
        chosen.put(photo.uri.toString(), photo);
    if (chosen.isEmpty()) return;
    if (chosen.size() != selectedPhotos.size()) {
      feedback(StatusView.Symbol.FAILED, "選択した写真を確認できませんでした。選択は保持されています", null, "");
      return;
    }
    selectionTags = new SelectedPhotoTags(this, new ArrayList<>(chosen.values()), actions);
    selectionTags.show();
  }

  private void toggleVisibleSelection() {
    showShareSelectionNotice(false);
    boolean remove = allVisibleSelected();
    for (List<Integer> members : displayed)
      for (int member : members) {
        if (photos.get(member).isVideo()) continue;
        String key = photos.get(member).uri.toString();
        if (remove) selectedPhotos.remove(key);
        else selectedPhotos.add(key);
      }
    adapter.notifyDataSetChanged();
    updateSelection();
  }

  private void libraryMenu() {
    MoreMenuUi.show(
        this,
        "ライブラリ",
        new String[] {"タグ", "設定とその他"},
        (dialog, which) -> {
          if (which == 0) openTags(null);
          else galleryMenu();
        });
  }

  private void selectionControls() {
    DiagnosticLog.event("screen.selectionControls", 0);
    selectionCount = new SelectionUi.Summary(this);
    root.addView(selectionCount);
    selectionRankStatus = status(StatusView.Symbol.RUNNING, "", "順位を読み込んでいます");
    selectionRankStatus.setTag("selection-rank-status");
    root.addView(selectionRankStatus);
    SelectionUi.BestSwitch check = new SelectionUi.BestSwitch(this);
    check.setChecked(bestOnly);
    check.setOnClickListener(GalleryUi::feedback);
    check.setOnCheckedChangeListener(
        (v, enabled) -> {
          showShareSelectionNotice(false);
          bestOnly = enabled;
          updateSelection();
        });
    root.addView(check, new LinearLayout.LayoutParams(-1, -2));
    selectionBulk = null;
    bulkSavedNotice = null;
    if (burstMembers != null) {
      selectionBulk = new BulkBestSheet.Entry(this, this::confirmBulkBest);
      root.addView(selectionBulk, new LinearLayout.LayoutParams(-1, -2));
    }
    selectionShareNotice = label("共有する写真が変わりました。もう一度「共有」を押して確認してください", 14, TEXT);
    selectionShareNotice.setTag("selection-share-notice");
    selectionShareNotice.setPadding(dp(16), dp(6), dp(16), dp(6));
    selectionShareNotice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    selectionShareNotice.setVisibility(selectionShareNeedsReview ? View.VISIBLE : View.GONE);
    root.addView(selectionShareNotice, new LinearLayout.LayoutParams(-1, -2));
    selectionShare = SelectionUi.action(this, "共有", true, this::shareSelection);
    selectionSort = SelectionUi.action(this, "比較", false, () -> shareSelection(true));
    selectionTag = SelectionUi.action(this, "タグ", false, this::tagSelectedPhotos);
    root.addView(LibraryUi.actionBar(this, selectionTag, selectionSort, selectionShare));
    updateSelection();
  }

  private void updateSelection() {
    if (!selecting || selectionCount == null) return;
    SelectionResult result = selectionResult();
    subtitle.setText(selectedPhotos.size() + "枚選択");
    subtitle.setVisibility(selectedPhotos.isEmpty() ? View.GONE : View.VISIBLE);
    Set<String> visible = new HashSet<>();
    for (List<Integer> members : displayed)
      for (int member : members) visible.add(photos.get(member).uri.toString());
    int hidden = 0;
    for (String key : selectedPhotos) if (!visible.contains(key)) hidden++;
    List<Integer> bulkTargets = selectionBulk == null ? List.of() : selectedBurstMembers();
    int outside = selectionBulk == null ? 0 : selectedPhotos.size() - bulkTargets.size();
    boolean rankUnavailable = result.retryRanking;
    selectionCount.setVisibility(rankUnavailable ? View.GONE : View.VISIBLE);
    selectionRankStatus.setVisibility(rankUnavailable ? View.VISIBLE : View.GONE);
    if (rankUnavailable) {
      if (result.ranks.phase() == RankingStore.Phase.PENDING) {
        selectionRankStatus.begin("順位を読み込んでいます");
      } else {
        selectionRankStatus.show(
            StatusView.Symbol.FAILED,
            "",
            "順位を読み込めませんでした",
            () -> {
              rankingStore.retryLoad();
              presentedSelection = null;
              updateSelection();
            },
            "再試行");
      }
    }
    selectionCount.counts(
        result.photos.size(),
        hidden - outside,
        result.unresolved.size(),
        outside,
        !selectedPhotos.isEmpty());
    if (selectionBulk != null) updateBulkBest(bulkTargets);
    if (selectionTitle != null)
      selectionTitle.setText(selectedPhotos.isEmpty() ? "写真を選択" : selectedPhotos.size() + "枚を選択");
    selectionSort.setEnabled(!rankUnavailable && !result.unresolved.isEmpty());
    selectionTag.setEnabled(!selectedPhotos.isEmpty());
    ShareCapacityChecks.Snapshot capacity =
        shareCapacity.request(rankUnavailable ? List.of() : result.photos);
    selectionCount.capacity(result.photos.isEmpty() ? null : capacity.available);
    selectionShare.setEnabled(
        !rankUnavailable
            && ((!result.photos.isEmpty() && capacity.available.actionable())
                || (result.photos.isEmpty() && !result.unresolved.isEmpty())));
    selectionShare.setContentDescription(
        "共有"
            + (!rankUnavailable && !result.photos.isEmpty()
                ? "。" + capacity.available.description()
                : ""));
    selectionShare.setStateDescription(selectionShare.isEnabled() ? null : "使えません");
    String allLabel = allVisibleSelected() ? "表示分を解除" : "表示分を選択";
    selectionAll.setText(allLabel);
    selectionAll.setContentDescription(allVisibleSelected() ? "表示中の写真の選択を解除" : "表示中の写真をすべて選択");
    selectionAll.setTooltipText(allLabel);
  }

  private List<Integer> selectedBurstMembers() {
    List<Integer> targets = new ArrayList<>();
    for (int member : burstMembers) {
      if (selectedPhotos.contains(photos.get(member).uri.toString())) targets.add(member);
    }
    return targets;
  }

  private Set<String> rankingIds(List<Integer> targets) throws IOException {
    Set<String> ids = new HashSet<>();
    for (int member : targets) ids.add(ArchiveStore.key(photos.get(member)));
    return ids;
  }

  private void updateBulkBest(List<Integer> targets) {
    if (rankingStore.snapshot().phase() == RankingStore.Phase.PENDING) {
      selectionBulk.update("同率1位にする", "順位を読み込んでいます", "順位を読み込んでいます");
      return;
    }
    if (targets.isEmpty()) {
      selectionBulk.update("同率1位にする", "同率1位にする。この連写の写真を選ぶと使えます", "この連写の写真を選ぶと使えます");
      return;
    }
    try {
      PhotoRanking saved = rankingFor(burstMembers);
      if (saved.done() && new HashSet<>(saved.best()).containsAll(rankingIds(targets))) {
        selectionBulk.update("選んだ写真はすべて1位", "選んだ写真はすべて1位です", "選んだ写真はすべて1位です");
        return;
      }
    } catch (IOException | IllegalArgumentException ignored) {
      // Let the action surface the load error and offer a retry.
    }
    int count = targets.size();
    String label =
        count == 1 ? "1枚を1位に" : count + (count == burstMembers.size() ? "枚すべてを" : "枚を") + "同率1位に";
    selectionBulk.update(label, "選んだ" + label + "する", null);
  }

  private void confirmBulkBest() {
    List<Integer> members = List.copyOf(burstMembers);
    List<Integer> targets = selectedBurstMembers();
    if (targets.isEmpty()) return;
    RankingStore.Snapshot snapshot = rankingStore.snapshot();
    RankingStore.Burst basis;
    PhotoRanking original;
    Set<String> ids;
    try {
      basis = rankingBasis(snapshot, members);
      original = basis.ranking();
      ids = Set.copyOf(rankingIds(targets));
    } catch (IOException | IllegalArgumentException e) {
      reportUnreadableRanking(snapshot, this::confirmBulkBest);
      return;
    }
    Set<String> winners = new HashSet<>(original.best());
    if (winners.containsAll(ids)) return;

    Set<Integer> visible = new HashSet<>();
    for (List<Integer> tile : displayed) visible.addAll(tile);
    List<Integer> ordinals = new ArrayList<>();
    Set<Integer> targetMembers = new HashSet<>(targets);
    int hidden = 0;
    for (int i = 0; i < members.size(); i++) {
      int member = members.get(i);
      if (targetMembers.contains(member)) {
        ordinals.add(i + 1);
        if (!visible.contains(member)) hidden++;
      }
    }
    int count = ids.size(), total = members.size();
    boolean all = count == total;
    int already = 0;
    for (String id : ids) if (winners.contains(id)) already++;
    String result = count == 1 ? "1枚を1位に" : count + "枚を同率1位に";
    String action = original.done() ? result + "する" : count + "枚を1位にする";
    String summary;
    if (original.done()) {
      summary =
          all ? total + "枚すべてが同率1位になります。" : "いまの1位（" + winners.size() + "枚）はそのまま。ほかの順位の並びも保ちます。";
      if (already > 0) summary += "うち" + already + "枚はすでに1位です。";
    } else {
      summary =
          all
              ? total + "枚すべてを同率1位にします。"
              : "選んだ"
                  + count
                  + (count == 1 ? "枚を1位、" : "枚を同率1位、")
                  + "ほかの"
                  + (total - count)
                  + "枚を同率"
                  + (count + 1)
                  + "位にします。";
      if (original.decisions() > 0) summary += "途中の比較は置き換えます。";
    }

    int screen = generation;
    RankingStore store = rankingStore;
    BulkBestSheet[] sheet = new BulkBestSheet[1];
    sheet[0] =
        new BulkBestSheet(
            this,
            original.done(),
            action,
            summary,
            hidden,
            selectedPhotos.size() - targets.size(),
            ordinals,
            (ordinal, image) -> previewInto(image, photos.get(members.get(ordinal - 1)), dp(40)),
            done -> {
              long request = DiagnosticLog.id();
              DiagnosticLog.event("ranking.bulk.start", request, "count", count);
              store
                  .submitBurst(basis, () -> basis.ranking().addCoWinners(ids))
                  .whenCompleteAsync(
                      (saved, error) -> {
                        boolean persisted = rankingAccepted(saved, error);
                        DiagnosticLog.event(
                            persisted ? "ranking.bulk.saved" : "ranking.bulk.failed", request);
                        if (isDestroyed() || isFinishing() || store != rankingStore) return;
                        if (rankingReadConflict(saved)) {
                          sheet[0].dismiss();
                          if (generation == screen) rankChangedFeedback();
                          return;
                        }
                        done.accept(persisted);
                        if (persisted
                            && generation == screen
                            && selecting
                            && members.equals(burstMembers))
                          refreshAfterBulkBest(result + "しました", request);
                      },
                      main::post);
            });
    sheet[0].show();
  }

  private void refreshAfterBulkBest(String notice, long request) {
    GalleryPosition position = GalleryPosition.capture(grid, photos, displayed);
    rebuildDisplayed();
    updateRankEmpty(displayedRanks);
    adapter.notifyDataSetChanged();
    if (position != null) position.restore(grid, photos, displayed);
    updateSelection();

    FrameLayout gridFrame = (FrameLayout) body.getChildAt(0);
    if (bulkSavedNotice != null) gridFrame.removeView(bulkSavedNotice);
    TextView message = label(notice, 14, TEXT);
    message.setCompoundDrawablesRelative(
        StatusView.glyphDrawable(StatusView.Symbol.COMPLETE, dp(18), TEXT), null, null, null);
    message.setCompoundDrawablePadding(dp(8));
    bulkSavedNotice = message;
    message.setMinHeight(dp(48));
    message.setGravity(Gravity.CENTER_VERTICAL);
    message.setPadding(dp(14), dp(8), dp(14), dp(8));
    android.graphics.drawable.GradientDrawable noticeShape = GalleryUi.shape(this, 0xf01c1c1f, 24);
    noticeShape.setStroke(dp(1), 0xff3a3a3f);
    message.setBackground(noticeShape);
    message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
    params.setMargins(dp(16), 0, dp(16), dp(8));
    gridFrame.addView(message, params);
    message.postDelayed(
        () -> {
          gridFrame.removeView(message);
          if (bulkSavedNotice == message) bulkSavedNotice = null;
        },
        4000);

    View screen = root;
    screen
        .getViewTreeObserver()
        .addOnPreDrawListener(
            new ViewTreeObserver.OnPreDrawListener() {
              @Override
              public boolean onPreDraw() {
                screen.getViewTreeObserver().removeOnPreDrawListener(this);
                if (screen.isHardwareAccelerated())
                  screen
                      .getViewTreeObserver()
                      .registerFrameCommitCallback(
                          () -> DiagnosticLog.event("ranking.bulk.frame", request));
                return true;
              }
            });
    screen.invalidate();
  }

  private void endSelection() {
    showShareSelectionNotice(false);
    selecting = false;
    shareCapacity.clearSelection();
    selectedPhotos.clear();
    selectionCount = null;
    selectionShare = null;
    selectionBulk = null;
    gallery();
  }

  private void shareSelection() {
    shareSelection(false);
  }

  private void shareSelection(boolean sorting) {
    DiagnosticLog.event("screen.shareSelection", 0);
    SelectionResult result = selectionResult();
    if (result.retryRanking) {
      reportUnreadableRanking(
          result.ranks,
          () -> {
            presentedSelection = null;
            updateSelection();
          });
      return;
    }
    if (!sorting
        && !result.photos.isEmpty()
        && !shareCapacity.snapshot(result.photos).available.actionable()) return;
    if (!sorting && (!result.photos.isEmpty() || !result.unresolved.isEmpty()))
      showShareSelectionNotice(false);
    if (!result.unresolved.isEmpty()) {
      shareDialog =
          new ShareUi.Confirmation(
              this,
              result.unresolved.size(),
              () -> {
                if (!isCurrentSelection(result)) {
                  shareSelectionChanged();
                  return;
                }
                selectionBurst = burstMembers == null ? null : new ArrayList<>(burstMembers);
                rankingFromSelection = true;
                burstMembers = new ArrayList<>(result.unresolved.get(0));
                openRanking();
              },
              result.photos.isEmpty()
                      || !shareCapacity.snapshot(result.photos).available.actionable()
                  ? null
                  : () -> {
                    if (!isCurrentSelection(result)) {
                      shareSelectionChanged();
                      return;
                    }
                    if (shareCapacity.snapshot(result.photos).available.actionable())
                      shareMenu(result.photos, result.ranks, () -> isCurrentSelection(result));
                  });
      shareDialog.show();
    } else if (!result.photos.isEmpty())
      shareMenu(result.photos, result.ranks, () -> isCurrentSelection(result));
  }

  private void leaveRanking() {
    if (rankingFromSelection) {
      burstMembers = selectionBurst;
      rankingFromSelection = false;
    }
    gallery();
  }

  private void chooseFilter() {
    if (selecting) return;
    DiagnosticLog.event("screen.photoFilter", 0);
    rankFilterSheet =
        new PhotoFilterSheet(
            this,
            filter,
            rankFilter,
            this::countPhotoFilter,
            this::applyPhotoFilter,
            () -> rankingStore.retryLoad());
    rankFilterSheet.show();
  }

  private void countPhotoFilter(
      String camera, int rank, java.util.function.Consumer<PhotoFilterSheet.CountResult> result) {
    // A queued index load posts its UI snapshot before this barrier reaches the UI thread.
    scans.execute(
        () ->
            main.post(
                () -> {
                  if (isDestroyed() || isFinishing()) return;
                  RankingStore owner = rankingStore;
                  RankingStore.Snapshot ranks = owner.snapshot();
                  if (rank != 0 && ranks.phase() != RankingStore.Phase.READY) {
                    result.accept(
                        ranks.phase() == RankingStore.Phase.PENDING
                            ? PhotoFilterSheet.CountResult.pending()
                            : PhotoFilterSheet.CountResult.failed());
                    return;
                  }
                  String currentCamera = filter, folder = albumFolder;
                  List<PhotoRepository.Photo> current = photos;
                  List<List<Integer>> groups = bundles;
                  BooleanSupplier valid =
                      () ->
                          current == photos
                              && groups == bundles
                              && Objects.equals(folder, albumFolder)
                              && (rank == 0 || owner == rankingStore && ranks == owner.snapshot());
                  scans.execute(
                      () -> {
                        int count = -1;
                        try {
                          List<PhotoRepository.Photo> candidates = current;
                          List<List<Integer>> candidateGroups = groups;
                          if (!camera.equals(currentCamera) || current.isEmpty())
                            try (GalleryIndex catalog = new GalleryIndex(this)) {
                              GalleryIndex.Snapshot loaded = catalog.load(folder, camera);
                              candidates = loaded.photos;
                              candidateGroups = loaded.bundles;
                            }
                          List<PhotoRepository.Photo> matching = candidates;
                          count = 0;
                          for (List<Integer> group :
                              RankedPhotoGroups.filter(
                                  candidates,
                                  candidateGroups,
                                  false,
                                  rank,
                                  ranks,
                                  members -> {
                                    List<String> ids = new ArrayList<>();
                                    for (int member : members)
                                      ids.add(ArchiveStore.key(matching.get(member)));
                                    return ranks.burst(ids).ranking();
                                  })) count += group.size();
                        } catch (Exception unavailable) {
                          count = -1;
                        }
                        int value = count;
                        main.post(
                            () -> {
                              if (isDestroyed() || isFinishing()) return;
                              if (!valid.getAsBoolean()) {
                                countPhotoFilter(camera, rank, result);
                                return;
                              }
                              result.accept(
                                  value < 0
                                      ? PhotoFilterSheet.CountResult.failed()
                                      : PhotoFilterSheet.CountResult.ready(value, valid));
                            });
                      });
                }));
  }

  private void applyPhotoFilter(String camera, int rank) {
    if (selecting || rank != 0 && rankingStore.snapshot().phase() != RankingStore.Phase.READY)
      return;
    rememberGallery();
    boolean reload = !filter.equals(camera) || photos.isEmpty();
    filter = camera;
    rankFilter = rank;
    rankCache.clear();
    burstMembers = null;
    if (reload) {
      photos = new ArrayList<>();
      bundles = new ArrayList<>();
    }
    gallery();
    if (reload) loadIndexedPhotos();
  }

  private void openTags(PhotoRepository.Photo photo) {
    Intent intent = new Intent(this, TagActivity.class);
    boolean albumRoot = albumHome && !detail && photo == null;
    intent.putExtra("folder", albumRoot ? null : albumFolder);
    intent.putExtra("camera", albumRoot ? "すべて" : filter);
    if (photo != null) intent.putExtra("photo", photo.uri.toString());
    startActivity(intent);
  }

  private android.app.Dialog galleryMenu() {
    DiagnosticLog.event("screen.galleryMenu", 0);
    return MoreMenuUi.show(
        this,
        "設定とその他の操作",
        new String[] {
          "クラウドと同期",
          "共有先の設定",
          "連写のまとめ方",
          "写真へのアクセス",
          "再読み込み",
          "写真のプレビュー",
          "処理の状況",
          "Nano Banana",
          "カメラから取り込む",
          "写真・動画の保存先"
        },
        (d, which) -> {
          switch (which) {
            case 9:
              startActivity(new Intent(this, PhotoStorageActivity.class));
              break;
            case 8:
              startActivity(CameraImportActivity.manualImportIntent(this, false));
              break;
            case 7:
              startActivity(new Intent(this, NanoBananaSettingsActivity.class));
              break;
            case 6:
              startActivity(new Intent(this, DashboardActivity.class));
              break;
            case 5:
              new GalleryUi.Sheet(this)
                  .setTitle("写真のプレビュー")
                  .setView(preparationStatus())
                  .setPositiveButton(
                      "もう一度準備する",
                      (dialog, w) -> {
                        PreviewPreparationJob.schedule(this);
                        feedback(StatusView.Symbol.PAUSED, "Loupeを閉じると準備を再開します", null, "");
                      })
                  .setNegativeButton("閉じる", null)
                  .show();
              break;
            case 0:
              startActivity(new Intent(this, CloudActivity.class));
              break;
            case 1:
              editTargets();
              break;
            case 2:
              var settings = SettingsRepository.open(this);
              BurstGapDialog.show(
                  this,
                  BurstGapSettings.readMillis(settings),
                  millis -> {
                    boolean changed = millis != BurstGapSettings.readMillis(settings);
                    if (!BurstGapSettings.saveMillis(settings, millis)) return false;
                    if (changed && pendingRestore != null) pendingRestore.remove("burst");
                    if (burstMembers != null) {
                      burstMembers = null;
                      gallery();
                    }
                    refreshIndex(true);
                    return true;
                  });
              break;
            case 3:
              new GalleryUi.Sheet(this)
                  .setTitle("写真へのアクセス")
                  .setItems(
                      new String[] {"写真へのアクセスを許可・変更", "RAWフォルダを追加"},
                      (dialog, choice) -> {
                        if (choice == 0) requestPhotoAccess();
                        else chooseFolder();
                      })
                  .setNegativeButton("閉じる", null)
                  .show();
              break;
            case 4:
              refreshIndex(true);
              break;
          }
        });
  }

  private String countText() {
    return burstMembers == null
        ? photos.size() + "枚 · " + bundles.size() + "組"
        : burstMembers.size() + "枚";
  }

  private void updateCount() {
    subtitle.setText(countText());
  }

  private int adjacentPhotoPosition(int direction) {
    if (burstMembers != null) {
      int next = burstMembers.indexOf(index) + direction;
      return next >= 0 && next < burstMembers.size() ? burstMembers.get(next) : -1;
    }
    for (int next = index + direction; next >= 0 && next < photos.size(); next += direction)
      if (!photos.get(next).isVideo()) return next;
    return -1;
  }

  private void adjacentPhoto(int direction) {
    int next = adjacentPhotoPosition(direction);
    if (next >= 0) showPhoto(next);
  }

  private void albumHome() {
    DiagnosticLog.event("screen.albumHome", 0);
    rememberGallery();
    if (viewing != null) viewing.cancel(true);
    rememberAlbums();
    albumHome = true;
    libraryAlbums = true;
    detail = false;
    rankingScreen = false;
    burstMembers = null;
    generation++;
    base();
    subtitle = new TextView(this);
    listStatus = new StatusView(this);
    listStatus.begin("アルバムを読み込んでいます");
    root.addView(
        LibraryUi.toolbar(
            this,
            "アルバム",
            LibraryUi.icon(this, R.drawable.ic_settings, "設定とその他", this::libraryMenu)));
    root.addView(LibraryUi.metadata(this, subtitle, listStatus));
    collectListHeader();
    body = vertical();
    root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
    addLibraryTabs(true, true);
    loadAlbums();
  }

  private void collectListHeader() {
    listHeader = vertical();
    for (int i = 0; i < root.getChildCount(); ) {
      View child = root.getChildAt(i);
      if ("library-toolbar".equals(child.getTag())
          || "photo-filter-condition".equals(child.getTag())) {
        i++;
        continue;
      }
      root.removeViewAt(i);
      listHeader.addView(child);
    }
  }

  private void loadAlbums() {
    DiagnosticLog.event("screen.loadAlbums", burstRequest);
    int token = generation;
    long updateVersion = indexUpdateVersion;
    scans.execute(
        () -> {
          try (GalleryIndex catalog = new GalleryIndex(this)) {
            List<GalleryIndex.Album> albums = catalog.albums();
            main.post(
                () -> {
                  if (isDestroyed() || !albumHome || token != generation) return;
                  subtitle.setText(albums.size() + "件");
                  if (albums.isEmpty()) listStatus.show(StatusView.Symbol.EMPTY, "写真がありません");
                  else listStatus.finish();
                  rememberAlbums();
                  body.removeAllViews();
                  // ListView owns attached headers; update its adapter instead of reparenting one.
                  ScrollingGrid albumGrid =
                      listHeader.getParent() instanceof ScrollingGrid
                          ? (ScrollingGrid) listHeader.getParent()
                          : new ScrollingGrid(this);
                  if (listHeader.getParent() == null)
                    albumGrid.addHeaderView(listHeader, null, false);
                  albumGridView = albumGrid;
                  AlbumListUi.configureGrid(this, albumGrid);
                  albumGrid.setCells(
                      new BaseAdapter() {
                        public int getCount() {
                          return albums.size();
                        }

                        public Object getItem(int p) {
                          return albums.get(p);
                        }

                        public long getItemId(int p) {
                          return p;
                        }

                        public View getView(int p, View reused, ViewGroup parent) {
                          LinearLayout cell;
                          if (reused instanceof LinearLayout) cell = (LinearLayout) reused;
                          else {
                            cell = AlbumListUi.cell(MainActivity.this);
                          }
                          GalleryIndex.Album album = albums.get(p);
                          ImageView cover = (ImageView) cell.getChildAt(0);
                          TextView caption = (TextView) cell.getChildAt(1);
                          caption.setText(album.name());
                          ((TextView) cell.getChildAt(2)).setText(album.count + "枚");
                          cell.setContentDescription(
                              "アルバム、" + album.folder + "、" + album.count + "枚");
                          previewInto(
                              cover,
                              album.cover,
                              600,
                              success -> {
                                if (success && !reportedCover) {
                                  reportedCover = true;
                                  cover.postOnAnimation(
                                      () ->
                                          android.util.Log.i(
                                              "YohakuStartup",
                                              "album_cover_ms="
                                                  + (SystemClock.elapsedRealtime() - startedAt)));
                                }
                              });
                          return cell;
                        }
                      });
                  albumGrid.restoreCell(albumScroll, albumTop, albumHeader);
                  albumGrid.onCellClick(
                      (p, v, pos, id) -> {
                        rememberAlbums();
                        albumFolder = albums.get(pos).folder;
                        filter = "すべて";
                        rankCache.clear();
                        photos = new ArrayList<>();
                        bundles = new ArrayList<>();
                        gallery();
                        loadIndexedPhotos();
                      });
                  body.addView(albumGrid, new LinearLayout.LayoutParams(-1, -1));
                  if (updateVersion == indexUpdateVersion) pendingIndexUpdate = false;
                  if (!albums.isEmpty() && !reportedAlbums) {
                    reportedAlbums = true;
                    albumGrid.postOnAnimation(
                        () -> {
                          reportFullyDrawn();
                          android.util.Log.i(
                              "YohakuStartup",
                              "album_list_ms=" + (SystemClock.elapsedRealtime() - startedAt));
                        });
                  }
                });
          } catch (Exception e) {
            main.post(
                () -> {
                  if (!isDestroyed() && albumHome && token == generation)
                    listStatus.show(
                        StatusView.Symbol.FAILED, "", "アルバムを読み込めません", this::refresh, "再試行");
                });
          }
        });
  }

  private void loadIndexedPhotos() {
    DiagnosticLog.event("screen.loadIndexedPhotos", burstRequest);
    int token = generation;
    long updateVersion = indexUpdateVersion;
    if (listStatus != null) listStatus.begin("写真の一覧を読み込んでいます");
    String folder = albumFolder, selected = filter;
    scans.execute(
        () -> {
          try (GalleryIndex catalog = new GalleryIndex(this)) {
            GalleryIndex.Snapshot snapshot = catalog.load(folder, selected);
            main.post(
                () -> {
                  if (isDestroyed() || token != generation || !libraryScreen().showsPhotoList())
                    return;
                  GalleryPosition position = GalleryPosition.capture(grid, photos, displayed);
                  rankCache.clear();
                  photos = snapshot.photos;
                  bundles = snapshot.bundles;
                  indexedBurstGapMillis = snapshot.burstGapMillis;
                  if (updateVersion == indexUpdateVersion) pendingIndexUpdate = false;
                  DiagnosticLog.event("screen.index_applied", 0, "count", photos.size());
                  burstMembers = null;
                  if (pendingRestore != null
                      && (photos.isEmpty() || indexedBurstGapMillis == savedBurstGapMillis())) {
                    Bundle restored = pendingRestore;
                    pendingRestore = null;
                    selecting = restored.getBoolean("selecting");
                    bestOnly = restored.getBoolean("bestOnly", true);
                    ArrayList<String> chosen = restored.getStringArrayList("selection");
                    if (chosen != null) selectedPhotos.addAll(chosen);
                    Set<String> available = new HashSet<>();
                    for (PhotoRepository.Photo photo : photos) available.add(photo.uri.toString());
                    selectedPhotos.retainAll(available);
                    burstMembers = restoredBurst(restored.getStringArrayList("burst"));
                    gallery();
                    return;
                  }
                  rebuildDisplayed();
                  updateRankEmpty(displayedRanks);
                  adapter.notifyDataSetChanged();
                  if (position == null) restoreGallery();
                  else position.restore(grid, photos, displayed);
                  subtitle.setText(countText());
                  listStatus.finish();
                });
          } catch (Exception e) {
            main.post(
                () -> {
                  if (!isDestroyed() && !albumHome && token == generation)
                    listStatus.show(
                        StatusView.Symbol.FAILED, "", "一覧を読み込めませんでした", this::refresh, "再試行");
                });
          }
        });
  }

  /** A restored burst must still contain precisely the same photos after reindexing. */
  private List<Integer> restoredBurst(List<String> scope) {
    if (scope == null || scope.size() < 2) return null;
    Set<String> expected = new HashSet<>(scope);
    for (List<Integer> members : bundles) {
      if (members.size() != scope.size()) continue;
      Set<String> current = new HashSet<>();
      for (int member : members) current.add(photos.get(member).uri.toString());
      if (current.equals(expected)) return new ArrayList<>(members);
    }
    return null;
  }

  private void refresh() {
    DiagnosticLog.event("screen.refresh", 0);
    if (!detail) {
      if (albumHome) loadAlbums();
      else if (burstMembers == null) loadIndexedPhotos();
    }
    refreshIndex(false);
  }

  private void refreshIndex(boolean force) {
    if (!refreshRequests.start(force)) return;
    Context app = getApplicationContext();
    GalleryIndex.WORKER.execute(
        () -> {
          Exception failure = null;
          try {
            GalleryIndex.rebuild(app, force, () -> false);
          } catch (Exception e) {
            failure = e;
          }
          final boolean failed = failure != null;
          main.post(
              () -> {
                int next = refreshRequests.finish();
                if (isDestroyed()) return;
                if (failed) {
                  feedback(
                      StatusView.Symbol.FAILED,
                      "写真の一覧を更新できませんでした。保存済みの一覧を表示しています。",
                      this::refresh,
                      "再読み込み");
                }
                if (!failed) {
                  // A preceding background job may have published the current index.
                  pendingIndexUpdate = true;
                  indexUpdateVersion++;
                  applyPendingIndexUpdate();
                }
                if (next != IndexRefreshRequests.NONE)
                  refreshIndex(next == IndexRefreshRequests.FORCE);
              });
        });
  }

  private void applyPendingIndexUpdate() {
    if (!pendingIndexUpdate) return;
    LibraryScreen shown = libraryScreen();
    if (shown == LibraryScreen.ALBUMS) loadAlbums();
    else if (shown.showsPhotoList()) loadIndexedPhotos();
  }

  private void requestPhotoAccess() {
    if (Build.VERSION.SDK_INT >= 34)
      requestPermissions(
          new String[] {
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
          },
          1);
    else if (Build.VERSION.SDK_INT >= 33)
      requestPermissions(
          new String[] {
            Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO
          },
          1);
    else requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
  }

  protected void onResume() {
    super.onResume();
    rankingForeground = true;
    watchRankings();
    quickImportOpening = false;
    var filter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED);
    filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
    if (Build.VERSION.SDK_INT >= 33)
      registerReceiver(usbChanges, filter, Context.RECEIVER_EXPORTED);
    else registerReceiver(usbChanges, filter);
    usbReceiverRegistered = true;
    jp.nano.rawgallery.camera.ImportProgress.INSTANCE.addListener(cameraImportChanged);
    refreshCameraImportBand.run();
    historyForeground = true;
    getSharedPreferences(ShareHistoryReceiver.PREFS, 0)
        .registerOnSharedPreferenceChangeListener(historyChanges);
    loadShareHistory();
    showShareHistoryFailure();
    if (repository != null) {
      if (resumed) {
        presentedSelection = null;
        refresh();
      } else {
        resumed = true;
        refreshIndex(false);
      }
    }
  }

  private void chooseFolder() {
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
    i.addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    startActivityForResult(i, 2);
  }

  public void onRequestPermissionsResult(int code, String[] p, int[] results) {
    super.onRequestPermissionsResult(code, p, results);
    refresh();
  }

  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == 2 && result == RESULT_OK && data != null && data.getData() != null) {
      try {
        getContentResolver()
            .takePersistableUriPermission(data.getData(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        refresh();
      } catch (SecurityException e) {
        feedback(StatusView.Symbol.PERMISSION, "読み取り許可を保存できませんでした", this::chooseFolder, "写真へのアクセス");
      }
    }
  }

  private StatusView listStatus;

  private StatusView status(StatusView.Symbol kind, String numbers, String message) {
    StatusView view = new StatusView(this);
    view.show(kind, numbers, message, null, "");
    return view;
  }

  private View preparationStatus() {
    JobState state = PreviewPreparationJob.snapshot(this);
    return status(
        StatusView.symbol(state.kind),
        state.countsKnown ? state.ready + "/" + state.total + " · " + state.failed : "—",
        (state.kind == JobState.Kind.UNKNOWN ? "現在の状態は不明です。最後の記録: " : "")
            + PreviewPreparationJob.status(this));
  }

  private void feedback(StatusView.Symbol state, String text, Runnable action, String label) {
    StatusFeedback.show(this, state, text, action, label);
  }

  private final class GalleryAdapter extends BaseAdapter {
    public int getCount() {
      return displayed.size();
    }

    public Object getItem(int p) {
      return photos.get(displayed.get(p).get(0));
    }

    public long getItemId(int p) {
      return p;
    }

    public View getView(int position, View recycled, android.view.ViewGroup parent) {
      RankingStore.Snapshot snapshot = displayedRanks;
      ThumbnailCell cell =
          recycled instanceof ThumbnailCell
              ? (ThumbnailCell) recycled
              : new ThumbnailCell(MainActivity.this);
      cell.fixedHeight = dp(PHOTO_TILE_HEIGHT_DP);
      ImageView image = cell.image;
      List<Integer> members = displayed.get(position);
      int representative = members.get(0);
      if (members.size() > 1)
        try {
          PhotoRanking saved = rankingFor(snapshot, members);
          if (saved.done())
            for (int member : members)
              if (saved.best().contains(ArchiveStore.key(photos.get(member)))) {
                representative = member;
                break;
              }
        } catch (Exception ignored) {
        }
      PhotoRepository.Photo p = photos.get(representative);

      cell.setContentDescription(
          p.name
              + "、"
              + (p.isVideo() ? "動画、" : "")
              + p.cameraGroup()
              + (p.isArchived() ? "、クラウド" : "、端末")
              + (members.size() > 1 ? "、連写" + members.size() + "枚" : ""));
      image.setImageDrawable(null);
      cell.badges.selectionMode = selecting;
      cell.badges.photoSelection = true;
      cell.badges.burstSelection = selecting && burstMembers != null;
      cell.badges.selected = selecting && tileSelected(members);
      cell.badges.partial = false;
      if (selecting && !cell.badges.selected)
        for (int member : members)
          if (selectedPhotos.contains(photos.get(member).uri.toString()))
            cell.badges.partial = true;
      cell.setSelected(cell.badges.selected || cell.badges.partial);
      int inset = cell.badges.selected ? dp(10) : 0;
      cell.setBackgroundColor(cell.badges.selected ? 0xff1e2b42 : 0xff22272a);
      image.setPadding(inset, inset, inset, inset);
      if (selecting)
        cell.setContentDescription(
            cell.getContentDescription()
                + (cell.badges.selected ? "、選択済み" : cell.badges.partial ? "、一部選択" : "、未選択"));
      cell.badges.shared(p.uri.toString(), historyFor(p));
      cell.setContentDescription(
          cell.getContentDescription() + ShareHistoryUi.description(cell.badges.shared));
      cell.badges.camera = "";
      cell.badges.video = p.isVideo();
      cell.badges.cloud = p.isArchived();
      cell.badges.count = members.size();
      cell.badges.rank = "";
      cell.badges.rankFailed = false;
      List<Integer> group = photoBundles.get(members.get(0));
      if (group != null && group.size() == 1 && !p.isVideo()) {
        try {
          if (snapshot.singleBest(ArchiveStore.key(p))) cell.badges.rank = "1位";
        } catch (Exception e) {
          rankReadDescription(cell, snapshot);
        }
      } else if (group != null && group.size() > 1) {
        try {
          PhotoRanking saved = rankingFor(snapshot, group);
          cell.badges.rank =
              saved.done()
                  ? (members.size() > 1 ? "1位" : saved.rankOf(ArchiveStore.key(p)) + "位")
                  : "";
        } catch (Exception e) {
          rankReadDescription(cell, snapshot);
        }
      }
      if (!cell.badges.rank.isEmpty())
        cell.setContentDescription(cell.getContentDescription() + "、" + cell.badges.rank);
      cell.badges.failed = false;
      cell.badges.invalidate();
      previewInto(
          image,
          p,
          400,
          success -> {
            cell.badges.failed = !success;
            cell.badges.invalidate();
            if (!success) cell.setContentDescription(cell.getContentDescription() + "、プレビューなし");
          });
      return cell;
    }
  }

  private void showPhoto(int position) {
    showPhoto(position, false);
  }

  private boolean returnToRanking;

  private static void rankReadDescription(ThumbnailCell cell, RankingStore.Snapshot snapshot) {
    boolean pending = snapshot.phase() == RankingStore.Phase.PENDING;
    cell.badges.rankFailed = !pending;
    cell.setContentDescription(
        cell.getContentDescription() + (pending ? "、順位を読み込み中" : "、順位を読み込めませんでした"));
  }

  /** Explicit singleton choice; never interpret a one-member comparison as a user choice. */
  private void singleBestAction(PhotoViewerUi viewer, PhotoRepository.Photo photo, int screen) {
    RankingStore store = rankingStore;
    SingleBestRecovery recovery = new SingleBestRecovery(viewer);
    final String key;
    try {
      key = ArchiveStore.key(photo);
    } catch (IOException e) {
      Button retry =
          viewer.action("1位", "1位", "順位を読み込めませんでした。押すと再試行", () -> showPhoto(index, false));
      unknownStar(retry, false);
      recovery.show("順位を読み込めませんでした", "順位を読み直す", retry::performClick);
      return;
    }
    boolean[] chosen = {false}, loaded = {false}, busy = {false}, writeFailed = {false};
    Button button = viewer.action("1位", "1位", "1位にする", () -> {});
    Runnable refresh =
        () -> {
          if (busy[0]) return;
          RankingStore.Snapshot snapshot = store.snapshot();
          loaded[0] = false;
          if (snapshot.phase() == RankingStore.Phase.READY) {
            try {
              boolean latest = snapshot.singleBest(key);
              if (latest != chosen[0]) writeFailed[0] = false;
              chosen[0] = latest;
              loaded[0] = true;
            } catch (Exception ignored) {
            }
          }
          if (loaded[0]) {
            PhotoViewerUi.bestState(button, chosen[0], false, writeFailed[0], false);
            if (writeFailed[0]) recovery.show("順位を保存できませんでした", "保存を再試行", button::performClick);
            else recovery.hide();
          } else {
            writeFailed[0] = false;
            boolean pending = snapshot.phase() == RankingStore.Phase.PENDING;
            unknownStar(button, pending);
            if (pending) recovery.hide();
            else recovery.show("順位を読み込めませんでした", "順位を読み直す", button::performClick);
          }
        };
    refreshSingleBest = refresh;
    refresh.run();
    button.setOnClickListener(
        v -> {
          if (busy[0]) return;
          GalleryUi.feedback(v);
          if (!loaded[0]) {
            store.retryLoad();
            refresh.run();
            return;
          }
          boolean expected = chosen[0];
          busy[0] = true;
          writeFailed[0] = false;
          recovery.hide();
          long request = DiagnosticLog.id();
          DiagnosticLog.event("ranking.single.start", request, "chosen", expected ? 0 : 1);
          PhotoViewerUi.bestState(button, expected, true, false, false);
          store
              .submitSingleBest(key, expected, !expected)
              .whenCompleteAsync(
                  (result, error) -> {
                    boolean saved = rankingAccepted(result, error);
                    DiagnosticLog.event(
                        saved ? "ranking.single.saved" : "ranking.single.failed", request);
                    if (isDestroyed() || isFinishing() || store != rankingStore) return;
                    busy[0] = false;
                    if (generation != screen || !detail) {
                      refreshSingleBest.run();
                      return;
                    }
                    boolean conflict = rankingReadConflict(result);
                    writeFailed[0] = !saved && !conflict;
                    refresh.run();
                    if (!loaded[0]) return;
                    if (conflict) {
                      button.announceForAccessibility("順位が変わりました。確認してからもう一度押してください");
                    } else if (saved && chosen[0] == !expected) {
                      button.announceForAccessibility(chosen[0] ? "1位にしました" : "1位を解除しました");
                    }
                    rankingFrame(button, "ranking.single.frame", request);
                  },
                  main::post);
        });
  }

  private final class SingleBestRecovery {
    private final PhotoViewerUi viewer;
    private LinearLayout row;
    private TextView message;
    private Button retry;

    SingleBestRecovery(PhotoViewerUi viewer) {
      this.viewer = viewer;
    }

    void show(String explanation, String retryLabel, Runnable action) {
      if (row == null) {
        row = row();
        row.setPadding(dp(8), 0, 0, dp(4));
        message = PhotoListUi.text(MainActivity.this, "", 14, 20, 0xffffb4a8);
        message.setTag("viewer-ranking-recovery");
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        row.addView(message, new LinearLayout.LayoutParams(0, -2, 1));
        retry = LibraryUi.text(MainActivity.this, "", () -> {});
        retry.setTag("viewer-ranking-retry");
        retry.setMinimumHeight(dp(48));
        retry.setMinHeight(dp(48));
        row.addView(retry, new LinearLayout.LayoutParams(-2, -2));
        viewer.footer.addView(row, 0, new LinearLayout.LayoutParams(-1, -2));
      }
      if (!explanation.contentEquals(message.getText())) message.setText(explanation);
      if (!retryLabel.contentEquals(retry.getText())) retry.setText(retryLabel);
      retry.setOnClickListener(v -> action.run());
      row.setVisibility(View.VISIBLE);
    }

    void hide() {
      if (row != null) row.setVisibility(View.GONE);
    }
  }

  private void unknownStar(Button button, boolean pending) {
    PhotoViewerUi.bestState(button, false, pending, !pending, true);
    ((com.google.android.material.button.MaterialButton) button).setCheckable(false);
    button.setText("1位");
    button.setCompoundDrawables(
        null,
        StatusView.glyphDrawable(
            pending ? StatusView.Symbol.RUNNING : StatusView.Symbol.FAILED, dp(24), TEXT),
        null,
        null);
    button.setContentDescription(pending ? "順位を読み込み中" : "順位を読み込めませんでした。押すと再試行");
    button.setStateDescription(pending ? "順位を読み込み中" : "読み込み失敗");
  }

  private void showPhoto(int position, boolean fromRanking) {
    watchRankings();
    DiagnosticLog.event("screen.photo", 0, "position", position);
    rememberGallery();
    if (position < 0 || position >= photos.size()) return;
    if (photos.get(position).isVideo()) {
      PhotoRepository.Photo video = photos.get(position);
      startActivity(
          new Intent(this, VideoActivity.class).setData(video.uri).putExtra("name", video.name));
      return;
    }
    if (viewing != null) viewing.cancel(true);
    detail = true;
    rankingScreen = false;
    returnToRanking = fromRanking;
    index = position;
    activePreview = null;
    int token = ++generation;
    PhotoRepository.Photo photo = photos.get(position);
    base();
    root.setBackgroundColor(Color.BLACK);
    PhotoViewerUi viewer = new PhotoViewerUi(this, photo);
    root.addView(viewer, new LinearLayout.LayoutParams(-1, 0, 1));
    FrameLayout photoFrame = viewer.stage;
    ZoomImageView image = viewer.image;
    String photoDescription = image.getContentDescription().toString();
    refreshShareBadges =
        () -> {
          viewer.shared.items(historyFor(photo), historyAnimating);
          image.setContentDescription(
              photoDescription + ShareHistoryUi.description(historyFor(photo)));
        };
    refreshShareBadges.run();
    image.setPageListener(
        direction -> {
          if (!fromRanking) adjacentPhoto(direction);
        });
    StatusView loadStatus = new StatusView(this);
    loadStatus.asPhotoOverlay();
    viewer.status(loadStatus);
    loadStatus.begin("元のファイルを読み込んでいます");
    TextView model = label("", 16, TEXT), dimensions = label(photo.name, 11, MUTED);
    if (!fromRanking) {
      int within = burstMembers == null ? position : burstMembers.indexOf(position);
      int count = burstMembers == null ? photos.size() : burstMembers.size();
      Button previous = viewer.action("前の写真", "前へ", "前の写真", () -> adjacentPhoto(-1));
      Button next = viewer.action("次の写真", "次へ", "次の写真", () -> adjacentPhoto(1));
      previous.setEnabled(adjacentPhotoPosition(-1) >= 0);
      next.setEnabled(adjacentPhotoPosition(1) >= 0);
    }
    viewer.zoom();
    if (!fromRanking
        && photoBundles.get(position) != null
        && photoBundles.get(position).size() == 1) singleBestAction(viewer, photo, token);
    Button editPhoto = PhotoEditor.add(this, viewer, photo, actions);
    viewer.action("共有", "共有", "共有（元のファイルを共有します）", () -> shareMenu(photo));
    if (!fromRanking)
      viewer.action(
          "Nano Banana", "AI編集", "Nano Bananaで写真を編集", () -> NanoBananaUi.show(this, photo));
    if (!fromRanking && "content".equals(photo.uri.getScheme()))
      viewer.action("タグ", "タグ", "タグ", () -> openTags(photo));
    viewer.action(
        "情報",
        "情報",
        "写真の情報",
        () -> {
          LinearLayout information = vertical();
          String[] storage = {"保存場所を確認しています。"};
          refreshShareInfo =
              () -> {
                information.removeAllViews();
                String metadata = model.getText() + "\n" + dimensions.getText() + "\n" + storage[0];
                information.addView(
                    ShareHistoryUi.information(
                        this, metadata, historyFor(photo), historyLoaded, historyReadFailed),
                    new LinearLayout.LayoutParams(-1, -2));
              };
          refreshShareInfo.run();
          AlertDialog dialog =
              new GalleryUi.Sheet(this)
                  .setTitle(photo.name)
                  .setView(information)
                  .setNeutralButton(
                      PhotoEditor.PICKER_ACTION,
                      (d, which) -> {
                        d.dismiss();
                        editPhoto.post(() -> editPhoto.performLongClick());
                      })
                  .setPositiveButton("閉じる", null)
                  .create();
          Future<?> locationRead =
              historyReads.submit(
                  () -> {
                    PhotoStorageLocation resolved = PhotoStorageLocation.resolve(this, photo);
                    String label =
                        resolved.kind() == PhotoStorageLocation.Kind.UNKNOWN
                            ? resolved.display()
                            : "保存場所　" + resolved.display();
                    if (!resolved.folder().isEmpty()) label += "\nフォルダ　" + resolved.folder();
                    String details = label;
                    main.post(
                        () -> {
                          if (!dialog.isShowing()
                              || isDestroyed()
                              || isFinishing()
                              || !detail
                              || token != generation) return;
                          storage[0] = details;
                          refreshShareInfo.run();
                        });
                  });
          dialog.setOnDismissListener(
              d -> {
                locationRead.cancel(true);
                refreshShareInfo = () -> {};
              });
          dialog.show();
          if (!historyLoaded || historyReadFailed) loadShareHistory();
        });
    if (fromRanking) {
      viewer.choose(
          () -> {
            if (!isCurrentCandidate(position)) return;
            boolean upper = Objects.equals(rankingMembers.get(ranking.upper()), position);
            saveRanking(candidateRank -> candidateRank.choose(upper));
          });
      View choose = viewer.footer.getChildAt(0);
      rankingActions.add(choose);
      rankingSaveIndicator(viewer.footer);
      refreshExpandedChoice =
          () -> {
            boolean current = isCurrentCandidate(position);
            choose.setVisibility(current ? View.VISIBLE : View.GONE);
            choose.setEnabled(current && !rankingBusy);
          };
      refreshExpandedChoice.run();
    }
    viewing =
        actions.submit(
            () ->
                ViewerPhotoLoad.read(
                    this,
                    repository,
                    photo,
                    main,
                    () -> !isDestroyed() && detail && token == generation,
                    new ViewerPhotoLoad.Display() {
                      @Override
                      public void preview(PhotoRepository.Preview preview, Bitmap bitmap) {
                        activePreview = preview;
                        image.setBitmap(bitmap);
                        model.setText(preview.model);
                        dimensions.setText(photo.name);
                        loadStatus.companion(StatusView.Symbol.PREVIEW);
                        viewer.refreshStatus();
                        image.setStateDescription("プレビューを表示しています。元のファイルを読み込んでいます");
                      }

                      @Override
                      public void previewUnavailable() {
                        model.setText("");
                      }

                      @Override
                      public void original(PhotoRepository.Preview full, Bitmap high) {
                        loadStatus.companion(null);
                        loadStatus.finish();
                        viewer.refreshStatus();
                        image.setStateDescription("元のファイルを表示しています");
                        activePreview = full;
                        image.setBitmap(high);
                        model.setText(
                            full.model
                                .replace("RICOH IMAGING COMPANY, LTD.", "RICOH")
                                .replace("/25128PNA1G", ""));
                        dimensions.setText(
                            full.width
                                + " × "
                                + full.height
                                + (photo.isJpeg() ? "  ·  元のファイル" : "  ·  RAWファイル内のJPEG")
                                + "\n"
                                + photo.name);
                      }

                      @Override
                      public void originalUnavailable() {
                        String failure =
                            activePreview == null
                                ? "写真を読み込めませんでした"
                                : "プレビューを表示しています。元のファイルを読み込めませんでした";
                        if (activePreview == null) {
                          viewer.failed();
                          photoFrame.removeView(loadStatus);
                          LinearLayout recovery = new LinearLayout(MainActivity.this);
                          recovery.setTag("viewer-original-recovery");
                          recovery.setOrientation(LinearLayout.VERTICAL);
                          recovery.setGravity(Gravity.CENTER);
                          loadStatus.asPhotoFailure();
                          loadStatus.show(StatusView.Symbol.FAILED, failure);
                          recovery.addView(
                              loadStatus, new LinearLayout.LayoutParams(dp(32), dp(32)));
                          Button retry = button("再試行", () -> showPhoto(position, fromRanking));
                          retry.setContentDescription(failure + "。再試行");
                          retry.setTextSize(14);
                          retry.setPadding(dp(18), 0, dp(18), 0);
                          MaterialUi.outlined(retry);
                          ActionIcon retryIcon =
                              new ActionIcon(
                                  "再試行", getResources().getDisplayMetrics().density * 20 / 26);
                          retryIcon.setBounds(0, 0, dp(20), dp(20));
                          retry.setCompoundDrawables(retryIcon, null, null, null);
                          retry.setCompoundDrawablePadding(dp(6));
                          recovery.addView(retry, new LinearLayout.LayoutParams(-2, dp(48)));
                          MaterialUi.outlined(retry);
                          LinearLayout.LayoutParams retryPosition =
                              (LinearLayout.LayoutParams) retry.getLayoutParams();
                          retryPosition.topMargin = dp(12);
                          retry.setLayoutParams(retryPosition);
                          photoFrame.addView(
                              recovery, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
                        } else
                          loadStatus.show(
                              StatusView.Symbol.FAILED,
                              "",
                              failure,
                              () -> showPhoto(position, fromRanking),
                              "再試行");
                        viewer.refreshStatus();
                        image.setStateDescription(failure);
                        if (activePreview == null) model.setText("");
                        dimensions.setText(photo.name);
                      }
                    }));
  }

  private LibraryScreen libraryScreen() {
    return LibraryScreen.shown(
        albumHome, detail, rankingScreen, selecting, burstMembers != null, albumFolder != null);
  }

  public void onBackPressed() {
    DiagnosticLog.event("action.back", 0, "screen", "MainActivity");
    switch (libraryScreen()) {
      case RANKING -> leaveRanking();
      case SELECTION -> endSelection();
      case PHOTO -> {
        if (returnToRanking) returnToComparison();
        else gallery();
      }
      case BURST -> {
        burstMembers = null;
        gallery();
      }
      case ALBUM_PHOTOS -> returnLibraryRoot();
      case ALBUMS, ALL_PHOTOS -> super.onBackPressed();
    }
  }

  private void openRanking() {
    DiagnosticLog.event("screen.openRanking", 0);
    watchRankings();
    RankingStore.Snapshot snapshot = rankingStore.snapshot();
    try {
      Map<String, Integer> members = new HashMap<>();
      for (int member : burstMembers) members.put(ArchiveStore.key(photos.get(member)), member);
      RankingStore.Burst basis = snapshot.burst(new ArrayList<>(members.keySet()));
      PhotoRanking displayed = displayRanking(basis);
      rankingMembers.clear();
      rankingMembers.putAll(members);
      rankingBasis = basis;
      ranking = displayed;
      rankingResultState = null;
      rankingResultFocus = null;
      showRanking();
    } catch (Exception e) {
      reportUnreadableRanking(snapshot, this::openRanking);
    }
  }

  private static PhotoRanking displayRanking(RankingStore.Burst basis) throws IOException {
    PhotoRanking displayed = basis.ranking();
    return displayed.journal.isEmpty() ? new PhotoRanking(displayed.ids, "B:") : displayed;
  }

  private boolean reconcileComparison(RankingStore.Snapshot snapshot) throws IOException {
    if (rankingBasis == null || rankingBusy) return false;
    RankingStore.Burst current = snapshot.burst(rankingBasis.ids());
    if (Objects.equals(current.storedJournal(), rankingBasis.storedJournal())) return false;
    ranking = displayRanking(current);
    rankingBasis = current;
    rankingResultState = null;
    rankingResultFocus = null;
    return true;
  }

  private void refreshComparison(RankingStore.Snapshot snapshot) {
    try {
      if (reconcileComparison(snapshot)) showRanking();
    } catch (IOException | IllegalArgumentException unreadable) {
      reportUnreadableRanking(snapshot, this::openRanking);
    }
  }

  private boolean isCurrentCandidate(int position) {
    return ranking != null
        && !ranking.done()
        && (Objects.equals(rankingMembers.get(ranking.upper()), position)
            || Objects.equals(rankingMembers.get(ranking.lower()), position));
  }

  private boolean refreshExpandedComparison(RankingStore.Snapshot snapshot, boolean writeFinished) {
    if (rankingBusy || rankingBasis == null) return false;
    try {
      boolean changed = reconcileComparison(snapshot);
      refreshExpandedChoice.run();
      if (changed || writeFinished || expandedRankUnavailable)
        rankingSaveStatus.setVisibility(View.GONE);
      expandedRankUnavailable = false;
      return true;
    } catch (IOException | IllegalArgumentException unreadable) {
      expandedRankUnavailable = true;
      for (View action : rankingActions) action.setEnabled(false);
      rankingMessage("順位を読み込めませんでした。戻って確認してください", true);
      return false;
    }
  }

  private void returnToComparison() {
    RankingStore.Snapshot snapshot = rankingStore.snapshot();
    try {
      reconcileComparison(snapshot);
      showRanking();
    } catch (IOException | IllegalArgumentException unreadable) {
      reportUnreadableRanking(snapshot, this::returnToComparison);
    }
  }

  private void saveRanking(UnaryOperator<PhotoRanking> change) {
    saveRanking(rankingBasis, ranking.journal, generation, change);
  }

  private void saveRanking(
      RankingStore.Burst basis,
      String displayedJournal,
      int screen,
      UnaryOperator<PhotoRanking> change) {
    if (rankingBusy || basis == null) return;
    if (screen != generation || basis != rankingBasis) {
      if (rankingScreen || detail && returnToRanking)
        rankingMessage("順位が変わりました。確認して選び直してください", false);
      return;
    }
    RankingStore store = rankingStore;
    List<String> members = basis.ids();
    rankingBusy = true;
    Map<View, Boolean> controls = new IdentityHashMap<>();
    for (View view : rankingActions) {
      controls.put(view, view.isEnabled());
      view.setEnabled(false);
    }
    TextView saveStatus = rankingSaveStatus;
    rankingMessage("順位を保存しています", false);
    store
        .submitBurst(basis, () -> change.apply(new PhotoRanking(members, displayedJournal)))
        .whenCompleteAsync(
            (result, error) -> {
              rankingBusy = false;
              if (isDestroyed() || isFinishing() || store != rankingStore) return;
              if (detail && returnToRanking && !rankingScreen) {
                if (refreshExpandedComparison(store.snapshot(), true)
                    && rankingBasis.ids().equals(members)) {
                  boolean saved = rankingAccepted(result, error);
                  if (rankingReadConflict(result)
                      || saved && !result.ranking().journal.equals(ranking.journal))
                    rankingMessage("順位が変わりました。戻って確認してください", false);
                  else if (!saved) rankingMessage("順位を保存できませんでした。もう一度選んでください", true);
                }
                return;
              }
              if (screen != generation || !rankingScreen) {
                if (rankingScreen && rankingBasis != null) {
                  RankingStore.Burst prior = rankingBasis;
                  refreshComparison(store.snapshot());
                  if (rankingBasis == prior) showRanking();
                }
                return;
              }
              if (saveStatus != null) saveStatus.setVisibility(View.GONE);
              controls.forEach(View::setEnabled);
              if (rankingAccepted(result, error) || rankingReadConflict(result)) {
                try {
                  RankingStore.Burst current = store.snapshot().burst(members);
                  ranking = displayRanking(current);
                  rankingBasis = current;
                  rankingResultState = null;
                  rankingResultFocus = null;
                  showRanking();
                  if (!rankingAccepted(result, error)
                      || !result.ranking().journal.equals(ranking.journal))
                    rankingMessage("順位が変わりました。確認して選び直してください", false);
                  return;
                } catch (IOException | IllegalArgumentException unreadable) {
                  rankingMessage("順位を読み込めませんでした。比較を開き直してください", true);
                  return;
                }
              }
              rankingMessage("順位を保存できませんでした。もう一度選んでください", true);
            },
            main::post);
  }

  private void rankingSaveIndicator(LinearLayout parent) {
    rankingSaveStatus = label("", 14, TEXT);
    rankingSaveStatus.setTag("ranking-save-status");
    rankingSaveStatus.setGravity(Gravity.CENTER);
    rankingSaveStatus.setPadding(dp(12), dp(4), dp(12), dp(4));
    rankingSaveStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    rankingSaveStatus.setVisibility(View.GONE);
    parent.addView(rankingSaveStatus, new LinearLayout.LayoutParams(-1, -2));
    if (rankingBusy) rankingMessage("順位を保存しています", false);
  }

  private void rankingMessage(String message, boolean failed) {
    if (rankingSaveStatus == null) return;
    rankingSaveStatus.setTextColor(failed ? 0xffffb4a8 : TEXT);
    rankingSaveStatus.setText(message);
    rankingSaveStatus.setVisibility(View.VISIBLE);
  }

  private void showRanking() {
    DiagnosticLog.event("screen.showRanking", 0);
    rememberGallery();
    if (viewing != null) viewing.cancel(true);
    detail = true;
    rankingScreen = true;
    int token = ++generation;
    base();
    if (ranking.done()) {
      showRankingResult(token);
      return;
    }
    LinearLayout top = vertical();
    top.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
    top.setPadding(dp(16), 0, dp(16), 0);
    top.setMinimumHeight(dp(52));

    TextView sortHeading = label(ranking.done() ? "ソート結果" : "ソート", 17, TEXT);
    sortHeading.setAccessibilityHeading(true);
    sortHeading.setGravity(Gravity.CENTER);
    sortHeading.setIncludeFontPadding(false);
    sortHeading.setMinHeight(dp(22));
    sortHeading.setTypeface(
        android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
    top.addView(sortHeading);
    TextView comparisonCount = label("比較 " + (ranking.decisions() + 1) + "回目", 12, MUTED);
    comparisonCount.setGravity(Gravity.CENTER);
    comparisonCount.setIncludeFontPadding(false);
    comparisonCount.setMinHeight(dp(16));
    top.addView(comparisonCount);
    rankingSaveIndicator(top);
    LinearLayout modes =
        RankingModes.create(
            this,
            ranking.bestOnly(),
            () -> changeRankingMode(true),
            () -> changeRankingMode(false));

    Button undo = button("直前の操作を取り消す", () -> saveRanking(PhotoRanking::undo));
    undo.setText("取り消し");
    undo.setEnabled(!rankingBusy && ranking.decisions() > 0);
    Button chooseA =
        GalleryUi.neutralPrimary(button("Aを選ぶ", () -> saveRanking(model -> model.choose(true))));
    Button chooseB =
        GalleryUi.neutralPrimary(button("Bを選ぶ", () -> saveRanking(model -> model.choose(false))));
    chooseA.setEnabled(false);
    chooseB.setEnabled(false);
    Button tie = button("同じ順位", () -> saveRanking(PhotoRanking::tie));
    tie.setEnabled(false);
    rankingActions.addAll(List.of(undo, chooseA, chooseB, tie));
    ViewGroup modeChoices = (ViewGroup) modes.getChildAt(0);
    for (int i = 0; i < modeChoices.getChildCount(); i++) {
      View modeChoice = modeChoices.getChildAt(i);
      modeChoice.setEnabled(!rankingBusy);
      rankingActions.add(modeChoice);
    }

    LinearLayout comparison = new LinearLayout(this);
    comparison.setBackgroundColor(Color.BLACK);
    ImageView[] candidates = new ImageView[2];
    double[] ratios = new double[2];
    Runnable arrange =
        () -> {
          if (ratios[0] <= 0
              || ratios[1] <= 0
              || comparison.getWidth() == 0
              || comparison.getHeight() == 0) return;
          tie.setEnabled(!rankingBusy && !ranking.bestOnly());
          chooseA.setEnabled(!rankingBusy);
          chooseB.setEnabled(!rankingBusy);
          boolean horizontal =
              ComparisonLayout.horizontal(
                  comparison.getWidth(), comparison.getHeight(), ratios[0], ratios[1]);
          int orientation = horizontal ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL;
          if (comparison.getTag() != null && comparison.getTag().equals(orientation)) return;
          comparison.setTag(orientation);
          comparison.setOrientation(orientation);
          for (ImageView image : candidates)
            image.setLayoutParams(
                new LinearLayout.LayoutParams(horizontal ? 0 : -1, horizontal ? -1 : 0, 1));
        };
    comparison.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> arrange.run());
    rankingCandidate(ranking.upper(), true, token, comparison, candidates, ratios, arrange);
    rankingCandidate(ranking.lower(), false, token, comparison, candidates, ratios, arrange);

    LinearLayout controls = vertical();
    controls.setPadding(dp(16), dp(10), dp(16), dp(20));
    RankingUi.action(chooseA, true, 52, 15, null);
    RankingUi.action(chooseB, true, 52, 15, null);
    RankingUi.choice(chooseA, "A");
    RankingUi.choice(chooseB, "B");
    RankingUi.add(
        controls,
        RankingUi.pair(
            this,
            RankingUi.countedChoice(this, chooseA, ranking.selectionCount(ranking.upper())),
            RankingUi.countedChoice(this, chooseB, ranking.selectionCount(ranking.lower()))),
        0);
    Button zoomA =
        RankingUi.action(
            button("Aを拡大", () -> showRankedPhoto(rankingMembers.get(ranking.upper()))),
            false,
            48,
            13,
            "拡大");
    Button zoomB =
        RankingUi.action(
            button("Bを拡大", () -> showRankedPhoto(rankingMembers.get(ranking.lower()))),
            false,
            48,
            13,
            "拡大");
    RankingUi.action(tie, false, 48, 13, "同じ順位");
    RankingUi.add(
        controls, RankingUi.zooms(this, zoomA, ranking.bestOnly() ? null : tie, zoomB), 8);
    modes.setPadding(0, 0, 0, 0);
    modes.setBackgroundColor(RankingUi.BG);
    RankingUi.add(controls, modes, 8);
    Button more = button("その他", this::rankingMenu);
    more.setEnabled(!rankingBusy);
    rankingActions.add(more);
    RankingUi.addDock(controls, RankingUi.dock(this, undo, more), 8);
    root.addView(
        new RankingUi.Screen(this, top, comparison, controls, false),
        new LinearLayout.LayoutParams(-1, 0, 1));
  }

  private void showRankingResult(int token) {
    ListView results = new ListView(this);
    results.setDivider(null);
    results.setDividerHeight(0);
    results.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
    results.setItemsCanFocus(true);
    results.addHeaderView(
        RankingResultUi.heading(this, ranking.ids.size(), ranking.bestOnly()), null, false);
    results.setAdapter(
        new android.widget.BaseAdapter() {
          public int getCount() {
            return 1;
          }

          public Object getItem(int p) {
            return ranking;
          }

          public long getItemId(int p) {
            return p;
          }

          public boolean isEnabled(int p) {
            return false;
          }

          public View getView(int p, View recycled, ViewGroup parent) {
            return RankingResultUi.body(
                MainActivity.this,
                ranking,
                id -> photos.get(rankingMembers.get(id)).name,
                (id, image, failed) -> {
                  PhotoRepository.Photo photo = photos.get(rankingMembers.get(id));
                  int edge = Math.max(1, image.getWidth());
                  images.execute(
                      () -> {
                        if (isDestroyed() || token != generation) return;
                        try {
                          PhotoRepository.Preview preview =
                              repository.preview(photo, () -> isDestroyed() || token != generation);
                          if (isDestroyed() || token != generation) return;
                          Bitmap bitmap = PhotoRepository.bitmap(preview, edge);
                          main.post(
                              () -> {
                                if (!isDestroyed() && token == generation)
                                  image.setImageBitmap(bitmap);
                                else bitmap.recycle();
                              });
                        } catch (Exception e) {
                          main.post(
                              () -> {
                                if (!isDestroyed() && token == generation) failed.run();
                              });
                        }
                      });
                },
                (id, target) -> {
                  rankingResultState = results.onSaveInstanceState();
                  rankingResultPosition = results.getFirstVisiblePosition();
                  rankingResultOffset =
                      results.getChildAt(0) == null ? 0 : results.getChildAt(0).getTop();
                  rankingResultFocus = (String) target.getTag();
                  rankingResultInputFocus = target.hasFocus();
                  rankingResultAccessibilityFocus = target.isAccessibilityFocused();
                  showRankedPhoto(rankingMembers.get(id));
                },
                MainActivity.this::confirmResort);
          }
        });
    root.addView(results, new LinearLayout.LayoutParams(-1, 0, 1));
    if (rankingResultState != null) results.onRestoreInstanceState(rankingResultState);
    results.post(
        () -> {
          if (rankingResultState != null) {
            results.setSelectionFromTop(rankingResultPosition, rankingResultOffset);
            results
                .getViewTreeObserver()
                .addOnPreDrawListener(
                    new ViewTreeObserver.OnPreDrawListener() {
                      public boolean onPreDraw() {
                        results.getViewTreeObserver().removeOnPreDrawListener(this);
                        View target =
                            rankingResultFocus == null
                                ? null
                                : results.findViewWithTag(rankingResultFocus);
                        if (target != null) {
                          if (rankingResultInputFocus) target.requestFocus();
                          if (rankingResultAccessibilityFocus)
                            target.performAccessibilityAction(
                                android.view.accessibility.AccessibilityNodeInfo
                                    .ACTION_ACCESSIBILITY_FOCUS,
                                null);
                        }
                        return true;
                      }
                    });
          }
        });
    int first = root.getChildCount();
    LinearLayout controls = vertical();
    Button share =
        TagDetailUi.action(
            this,
            "共有",
            true,
            () -> {
              selecting = true;
              bestOnly = true;
              leaveRanking();
            });
    share.setCompoundDrawables(null, null, null, null);
    controls.addView(share, new LinearLayout.LayoutParams(-1, -2));
    Button mode =
        TagDetailUi.action(
            this,
            ranking.bestOnly() ? "すべての順位を決める" : "1位を決める",
            false,
            () -> changeRankingMode(!ranking.bestOnly()));
    mode.setText(ranking.bestOnly() ? "すべての順位を決める" : "1位を決める");
    mode.setEnabled(!rankingBusy);
    controls.addView(mode, new LinearLayout.LayoutParams(-1, -2));
    rankingSaveIndicator(controls);
    Button undo = TagDetailUi.dockButton(this, "直前の操作を取り消す", () -> saveRanking(PhotoRanking::undo));
    undo.setText("取り消し");
    undo.setEnabled(!rankingBusy && ranking.decisions() > 0);
    controls.addView(TagDetailUi.dock(this, undo));
    rankingActions.addAll(List.of(undo, mode));
    TagDetailUi.gaps(controls);
    root.addView(controls);
    TagDetailUi.footer(root, first);
  }

  private void confirmResort() {
    if (rankingBusy) return;
    RankingStore.Burst basis = rankingBasis;
    String journal = ranking.journal, mode = ranking.bestOnly() ? "B:" : "A:";
    int screen = generation;
    new GalleryUi.Sheet(this)
        .setTitle("この連写を再ソートしますか？")
        .setMessage("この連写の選択履歴を消して、最初から比較します。写真は変更しません。")
        .setPositiveButton(
            "再ソート",
            (dialog, which) ->
                saveRanking(basis, journal, screen, model -> new PhotoRanking(model.ids, mode)))
        .setNegativeButton("キャンセル", null)
        .show();
  }

  private void changeRankingMode(boolean best) {
    if (rankingBusy || ranking.bestOnly() == best) return;
    RankingStore.Burst basis = rankingBasis;
    String journal = ranking.journal;
    int screen = generation;
    new GalleryUi.Sheet(this)
        .setTitle("ソート方式を変更しますか？")
        .setMessage("この連写の結果と途中の判断を置き換え、最初から比較します。")
        .setPositiveButton(
            "変更",
            (d, w) ->
                saveRanking(
                    basis,
                    journal,
                    screen,
                    model -> new PhotoRanking(model.ids, best ? "B:" : "A:")))
        .setNegativeButton("キャンセル", null)
        .show();
  }

  private void rankingMenu() {
    new GalleryUi.Sheet(this)
        .setItems(
            new String[] {"再ソート"},
            (d, choice) -> {
              confirmResort();
            })
        .show();
  }

  private void showRankedPhoto(int member) {
    showPhoto(member, true);
  }

  private void rankingCandidate(
      String key,
      boolean upper,
      int token,
      LinearLayout comparison,
      ImageView[] candidates,
      double[] ratios,
      Runnable arrange) {
    PhotoRepository.Photo photo = photos.get(rankingMembers.get(key));
    ComparisonPhotoView image =
        new ComparisonPhotoView(this, upper ? "A" : "B", photo.name, ranking.selectionCount(key));
    image.setOnClickListener(
        v -> {
          if (rankingScreen && token == generation && ratios[0] > 0 && ratios[1] > 0) {
            GalleryUi.feedback(v);
            saveRanking(model -> model.choose(upper));
          }
        });
    image.setOnLongClickListener(
        v -> {
          if (token == generation) showRankedPhoto(rankingMembers.get(key));
          return true;
        });
    image.setEnabled(false);
    candidates[upper ? 0 : 1] = image;
    rankingActions.add(image);
    comparison.addView(image, new LinearLayout.LayoutParams(-1, 0, 1));
    images.execute(
        () -> {
          try {
            Bitmap bitmap = PhotoRepository.bitmap(repository.preview(photo), 1600);
            main.post(
                () -> {
                  if (isDestroyed() || token != generation) {
                    bitmap.recycle();
                    return;
                  }
                  image.setImageBitmap(bitmap);
                  image.loaded();
                  ratios[upper ? 0 : 1] = (double) bitmap.getWidth() / bitmap.getHeight();
                  if (ratios[0] > 0 && ratios[1] > 0) {
                    arrange.run();
                    for (ImageView candidate : candidates) candidate.setEnabled(!rankingBusy);
                  }
                });
          } catch (Exception e) {
            main.post(
                () -> {
                  if (!isDestroyed() && token == generation) {
                    image.failed();
                    feedback(
                        StatusView.Symbol.FAILED,
                        "写真を読み込めませんでした。一覧へ戻って開き直してください。",
                        this::leaveRanking,
                        "一覧へ");
                  }
                });
          }
        });
  }

  private void shareMenu(PhotoRepository.Photo photo) {
    shareMenu(List.of(photo));
  }

  private Dialog shareDialog;

  private void shareMenu(List<PhotoRepository.Photo> selection) {
    shareMenu(selection, rankingStore.snapshot(), () -> true);
  }

  private void shareMenu(
      List<PhotoRepository.Photo> selection,
      RankingStore.Snapshot snapshot,
      BooleanSupplier validSelection) {
    List<PhotoRepository.Photo> frozen = List.copyOf(selection);
    Set<String> best = new HashSet<>();
    int cloud = 0;
    for (PhotoRepository.Photo photo : frozen) {
      if (photo.isArchived()) cloud++;
      int index = photos.indexOf(photo);
      List<Integer> group = photoBundles.get(index);
      if (group != null && snapshot.phase() == RankingStore.Phase.READY)
        try {
          if (group.size() == 1) {
            if (!photo.isVideo() && snapshot.singleBest(ArchiveStore.key(photo)))
              best.add(photo.uri.toString());
          } else {
            PhotoRanking rank = rankingFor(snapshot, group);
            if (rank.done() && rank.best().contains(ArchiveStore.key(photo)))
              best.add(photo.uri.toString());
          }
        } catch (Exception ignored) {
        }
    }
    ShareUi.Menu menu =
        new ShareUi.Menu(
            this,
            frozen,
            ShareTargets.load(this),
            cloud,
            best,
            (raw, target) -> export(frozen, raw, target, validSelection));
    capacityMenu = menu;
    menu.capacity(shareCapacity.request(frozen));
    shareDialog = menu;
    menu.setOnDismissListener(
        d -> {
          if (capacityMenu == menu) capacityMenu = null;
          for (ImageView image : menu.previews)
            if (image.getTag() instanceof ImageLoad) ((ImageLoad) image.getTag()).cancelled = true;
        });
    menu.show();
    for (int i = 0; i < menu.previews.size(); i++)
      previewInto(menu.previews.get(i), menu.previewPhotos.get(i), dp(52));
  }

  private void export(
      List<PhotoRepository.Photo> selection, boolean raw, ShareTargets.Target target) {
    export(selection, raw, target, () -> true);
  }

  private void export(
      List<PhotoRepository.Photo> selection,
      boolean raw,
      ShareTargets.Target target,
      BooleanSupplier validSelection) {
    if (shareProgress != null && shareProgress.isShowing()) return;
    if (!validSelection.getAsBoolean()) {
      shareSelectionChanged();
      return;
    }
    final int attempt = ++shareAttempt;
    shareProgress =
        new ShareUi.Preparing(
            this,
            selection.size(),
            raw,
            () -> {
              if (attempt != shareAttempt) return;
              shareAttempt++;
              if (sharing != null) sharing.cancel(true);
              feedback(StatusView.Symbol.CANCELLED, "共有の準備を中止しました", null, "");
            });
    shareProgress.show();
    sharing =
        actions.submit(
            () -> {
              try {
                SharePreparation.Result prepared = sharePreparation.prepare(selection, raw);
                ArrayList<Uri> uris = new ArrayList<>();
                for (File file : prepared.files)
                  uris.add(Uri.parse("content://jp.nano.rawgallery.share/" + file.getName()));
                ArrayList<String> historyKeys = new ArrayList<>();
                for (PhotoRepository.Photo photo : selection)
                  historyKeys.add(ArchiveStore.key(photo));
                final String historySession =
                    ShareHistoryReceiver.begin(
                        getApplicationContext(), historyKeys, System.currentTimeMillis());
                String type = prepared.mimeType;
                String action = uris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE;
                main.post(
                    () -> {
                      shareCapacity.observed(prepared.jpegSizes);
                      if (isDestroyed() || attempt != shareAttempt) {
                        ShareHistoryReceiver.cancelLater(getApplicationContext(), historySession);
                        return;
                      }
                      shareProgress.dismiss();
                      if (!validSelection.getAsBoolean()) {
                        ShareHistoryReceiver.cancelLater(getApplicationContext(), historySession);
                        shareSelectionChanged();
                        return;
                      }
                      Intent send =
                          new Intent(action)
                              .setType(type)
                              .putExtra(Intent.EXTRA_TITLE, selection.size() + "枚の写真")
                              .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                      if (uris.size() == 1) send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                      else send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                      ClipData clip = ClipData.newRawUri("写真", uris.get(0));
                      for (int i = 1; i < uris.size(); i++)
                        clip.addItem(new ClipData.Item(uris.get(i)));
                      send.setClipData(clip);
                      if (target != null) {
                        ComponentName component =
                            ComponentName.unflattenFromString(target.component);
                        boolean supported = false;
                        for (ResolveInfo candidate :
                            getPackageManager()
                                .queryIntentActivities(new Intent(action).setType(type), 0)) {
                          if (new ComponentName(
                                  candidate.activityInfo.packageName, candidate.activityInfo.name)
                              .equals(component)) {
                            supported = true;
                            break;
                          }
                        }
                        if (!supported) {
                          ShareHistoryReceiver.cancelLater(getApplicationContext(), historySession);
                          feedback(
                              StatusView.Symbol.FORMAT,
                              "この共有先は選んだ画像形式に対応していません。共有先の形式を変更してください。",
                              this::editTargets,
                              "共有先の設定");
                          return;
                        }
                        send.setComponent(component);
                      }
                      android.app.PendingIntent callback = null;
                      try {
                        Intent outgoing = send;
                        if (target == null) {
                          if (historySession != null)
                            callback = ShareHistoryReceiver.callback(this, historySession);
                          outgoing =
                              callback == null
                                  ? Intent.createChooser(send, "写真を共有")
                                  : Intent.createChooser(send, "写真を共有", callback.getIntentSender());
                        }
                        if (!validSelection.getAsBoolean()) {
                          if (callback != null) callback.cancel();
                          ShareHistoryReceiver.cancelLater(getApplicationContext(), historySession);
                          shareSelectionChanged();
                          return;
                        }
                        startActivity(outgoing);
                        if (target != null)
                          ShareHistoryReceiver.recordLater(
                              getApplicationContext(), historySession, send.getComponent());
                        else if (historySession == null)
                          ShareHistoryReceiver.failed(getApplicationContext());
                      } catch (RuntimeException failure) {
                        if (callback != null) callback.cancel();
                        ShareHistoryReceiver.cancelLater(getApplicationContext(), historySession);
                        if (!(failure instanceof ActivityNotFoundException)
                            && !(failure instanceof SecurityException)) throw failure;
                        feedback(
                            StatusView.Symbol.NO_APP,
                            "共有先が見つかりません。共有先の設定を変更してください。",
                            this::editTargets,
                            "共有先の設定");
                      }
                    });
              } catch (Exception e) {
                main.post(
                    () -> {
                      if (isDestroyed() || attempt != shareAttempt) return;
                      shareProgress.dismiss();
                      if (e instanceof SharePreparation.CapacityException) {
                        SharePreparation.CapacityException limit =
                            (SharePreparation.CapacityException) e;
                        shareCapacity.observed(limit.jpegSizes);
                        if (selecting) updateSelection();
                        ShareCapacityChecks.Snapshot capacity = shareCapacity.snapshot(selection);
                        ShareCapacity.Result other = raw ? capacity.jpeg : capacity.original;
                        shareDialog =
                            new ShareUi.OverLimit(
                                this,
                                selection.size(),
                                raw,
                                limit.bytes,
                                other.actionable()
                                    ? () -> export(selection, !raw, null, validSelection)
                                    : null);
                        shareDialog.show();
                        return;
                      }
                      shareDialog =
                          new ShareUi.Failure(
                              this,
                              selection.size(),
                              raw,
                              selection.size()
                                  + "枚の選択は保持されています。共有を準備できませんでした。空き容量・通信・読み取り許可を確認してください。",
                              () -> export(selection, raw, target, validSelection));
                      shareDialog.show();
                    });
              }
            });
  }

  private void editTargets() {
    DiagnosticLog.event("screen.editTargets", 0);
    targets = ShareTargets.load(this);
    List<String> options = new ArrayList<>();
    for (int i = 0; i < targets.size(); i++) {
      ShareTargets.Target t = targets.get(i);
      options.add((i + 1) + ". " + t.label + "  /  " + (t.raw ? "元のファイル" : "JPEG画像"));
    }
    options.add("＋ 共有先を追加");
    new GalleryUi.Sheet(this)
        .setTitle("共有先と並び順")
        .setItems(
            options.toArray(new String[0]),
            (d, i) -> {
              if (i == targets.size()) addTarget();
              else editTarget(i);
            })
        .setNegativeButton("閉じる", null)
        .show();
  }

  private void editTarget(int index) {
    ShareTargets.Target t = targets.get(index);
    new GalleryUi.Sheet(this)
        .setTitle(t.label)
        .setItems(
            new String[] {"JPEGで共有", "元の形式で共有", "一つ上へ移動", "一つ下へ移動", "登録を外す"},
            (d, choice) -> {
              if (choice == 0) t.raw = false;
              if (choice == 1) t.raw = true;
              if (choice == 2 && index > 0) Collections.swap(targets, index, index - 1);
              if (choice == 3 && index + 1 < targets.size())
                Collections.swap(targets, index, index + 1);
              if (choice == 4) targets.remove(index);
              ShareTargets.save(this, targets);
              editTargets();
            })
        .setNegativeButton("キャンセル", (d, w) -> editTargets())
        .setOnCancelListener(d -> editTargets())
        .show();
  }

  private void addTarget() {
    PackageManager pm = getPackageManager();
    LinkedHashMap<String, ResolveInfo> apps = new LinkedHashMap<>();
    for (String mime : RawFormats.mimeTypes())
      for (ResolveInfo r :
          pm.queryIntentActivities(new Intent(Intent.ACTION_SEND).setType(mime), 0)) {
        if (r.activityInfo.exported
            && r.activityInfo.enabled
            && !r.activityInfo.packageName.equals(getPackageName()))
          apps.put(
              new ComponentName(r.activityInfo.packageName, r.activityInfo.name).flattenToString(),
              r);
      }
    List<Map.Entry<String, ResolveInfo>> entries = new ArrayList<>(apps.entrySet());
    entries.sort(Comparator.comparing(e -> e.getValue().loadLabel(pm).toString()));
    String[] names = new String[entries.size()];
    for (int i = 0; i < entries.size(); i++)
      names[i] = entries.get(i).getValue().loadLabel(pm).toString();
    new GalleryUi.Sheet(this)
        .setTitle("登録するアプリ")
        .setItems(
            names,
            (d, i) -> {
              String component = entries.get(i).getKey();
              for (ShareTargets.Target t : targets)
                if (t.component.equals(component)) {
                  feedback(StatusView.Symbol.ALREADY, "登録済みです", null, "");
                  return;
                }
              targets.add(new ShareTargets.Target(component, names[i], false));
              ShareTargets.save(this, targets);
              editTargets();
            })
        .setNegativeButton("キャンセル", (d, w) -> editTargets())
        .setOnCancelListener(d -> editTargets())
        .show();
  }

  @Override
  protected void onPause() {
    rankingForeground = false;
    stopWatchingRankings();
    if (usbReceiverRegistered) {
      unregisterReceiver(usbChanges);
      usbReceiverRegistered = false;
    }
    jp.nano.rawgallery.camera.ImportProgress.INSTANCE.removeListener(cameraImportChanged);
    main.removeCallbacks(refreshCameraImportBand);
    historyForeground = false;
    getSharedPreferences(ShareHistoryReceiver.PREFS, 0)
        .unregisterOnSharedPreferenceChangeListener(historyChanges);
    super.onPause();
  }

  protected void onDestroy() {
    stopWatchingRankings();
    mediaRefreshClosed = true;
    if (selectionTags != null) selectionTags.dismiss();
    generation++;
    shareAttempt++;
    if (shareProgress != null) shareProgress.dismiss();
    if (shareDialog != null) shareDialog.dismiss();
    if (rankFilterSheet != null) rankFilterSheet.dismiss();
    getContentResolver().unregisterContentObserver(mediaObserver);
    main.removeCallbacks(mediaChanged);
    historyReads.shutdownNow();
    shareCapacity.close();
    scans.shutdownNow();
    images.shutdownNow();
    actions.shutdownNow();
    super.onDestroy();
  }
}
