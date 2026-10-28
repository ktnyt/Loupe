package jp.nano.rawgallery;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.util.LruCache;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

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
  private PhotoRepository repository;
  private RankingStore rankingStore;
  private SharePreparation sharePreparation;
  private List<List<Integer>> bundles = new ArrayList<>();
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
    if (!albumHome && !detail && grid != null && grid.isAttachedToWindow()
        && shownGalleryKey != null && !photos.isEmpty()) {
      GalleryPosition position = GalleryPosition.capture(grid, photos, displayed);
      if (position != null) galleryScroll.put(shownGalleryKey, position);
    }
    if (!albumHome && !detail && albumFolder == null && burstMembers == null) {
      allPhotosFilter = filter; allPhotosRankFilter = rankFilter;
    }
  }

  private void rememberAlbums() {
    if (albumHome && albumGridView != null && albumGridView.isAttachedToWindow()
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
    rememberGallery(); rememberAlbums();
    DiagnosticLog.event("library.tab", 0, "albums", albums);
    libraryAlbums = albums;
    if (albums) albumHome();
    else {
      albumFolder = null; filter = allPhotosFilter; rankFilter = allPhotosRankFilter;
      burstMembers = null; rankCache.clear(); photos = new ArrayList<>(); bundles = new ArrayList<>();
      gallery(); loadIndexedPhotos();
    }
  }

  private void returnLibraryRoot() { switchLibraryTab(libraryAlbums); }

  private boolean selecting, bestOnly = true, rankingFromSelection;
  private final Set<String> selectedPhotos = new LinkedHashSet<>();
  private int rankFilter;
  private final Map<Integer, List<Integer>> photoBundles = new HashMap<>();
  private final Map<List<Integer>, PhotoRanking> rankCache = new HashMap<>();
  private List<Integer> selectionBurst;
  private SelectionUi.Summary selectionCount;
  private TextView selectionTitle;
  private Button selectionShare, selectionAll, selectionSort;
  private GalleryAdapter adapter;
  private String filter = "すべて";
  private String albumFolder;
  private boolean albumHome = true, resumed;
  private final IndexRefreshRequests refreshRequests = new IndexRefreshRequests();
  private boolean pendingIndexUpdate;
  private long indexUpdateVersion;
  private final long startedAt = SystemClock.elapsedRealtime();
  private boolean reportedAlbums, reportedCover;
  private final Runnable mediaChanged = () -> refresh();
  private final android.database.ContentObserver mediaObserver =
      new android.database.ContentObserver(main) {
        public void onChange(boolean selfChange) {
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

  public void onCreate(Bundle state) {
    super.onCreate(state);
    repository = new PhotoRepository(this);
    rankingStore = new RankingStore(this);
    sharePreparation = new SharePreparation(this, repository);
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
    if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) requestPhotoAccess();
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    rememberGallery(); rememberAlbums();
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
    root = vertical();
    root.setBackgroundColor(BG);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          Insets i = insets.getInsets(WindowInsets.Type.systemBars());
          v.setPadding(i.left, i.top, i.right, i.bottom);
          return insets;
        });
    setContentView(root);
  }

  private void gallery() {
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
    if (burstMembers != null && !selecting) { burstDetail(); return; }
    subtitle = label("", 14, MUTED);
    listStatus = new StatusView(this);
    PhotoListUi.Heading header = new PhotoListUi.Heading(this,
        burstMembers != null ? "連写" : albumFolder == null ? "すべての写真" : new File(albumFolder).getName(),
        subtitle, listStatus);
    selectionTitle = selecting ? header.title : null;
    if(selecting)header.setPadding(dp(20),dp(40),dp(20),dp(16));

    if (!selecting && burstMembers == null) addOtherAlbums();
    root.addView(header);
    collectListHeader();
    body = vertical();
    root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
    grid = new ScrollingGrid(this);
    grid.setVerticalScrollBarEnabled(false);
    grid.addHeaderView(listHeader, null, false);
    grid.setNumColumns(4);
    grid.setHorizontalSpacing(dp(2));
    grid.setVerticalSpacing(dp(2));
    grid.setPadding(0, 0, 0, 0);
    grid.setClipToPadding(false);
    displayed = new ArrayList<>();
    rebuildDisplayed();
    adapter = new GalleryAdapter();
    grid.setCells(adapter);
    shownGalleryKey = albumFolder + "|" + filter + "|" + rankFilter + "|" + String.valueOf(burstMembers);
    restoreGallery();
    grid.onCellClick(
        (p, v, pos, id) -> {
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
    StatusView empty = status(StatusView.Symbol.EMPTY,"","表示する写真がありません");
    gridFrame.addView(empty, new FrameLayout.LayoutParams(-1, -1));
    grid.setEmptyView(empty);
    body.addView(gridFrame, new LinearLayout.LayoutParams(-1, -1));
    int controlsStart = root.getChildCount();
    if (selecting) { selectionControls(); }
    else {
      addRankFilters();
      Button select = TagDetailUi.dockButton(this,"選択", () -> {
        if (!photos.isEmpty()) { selecting = true; bestOnly = true; gallery(); }
      });
      root.addView(TagDetailUi.dock(this,select, TagDetailUi.dockButton(this,"タグ", () -> openTags(null)), TagDetailUi.dockButton(this,"その他", this::photoMenu)));
    }
    if(selecting)SelectionUi.finishFooter(root,controlsStart);else GalleryUi.bottomControls(root, controlsStart, BG);
    if (albumFolder == null && burstMembers == null)
      root.addView(new LibraryTabs(this, false, !selecting, this::switchLibraryTab));
    if (selecting) updateSelection();
    else if (!photos.isEmpty()) updateCount();
    applyPendingIndexUpdate();
  }

  private void photoMenu() {
    DiagnosticLog.event("screen.photoMenu", 0);
    MoreMenuUi.show(this,"その他",new String[] {"カメラで絞り込み", "タグ", "設定とその他"}, (d, which) -> {
      if (which == 0 && !selecting) chooseFilter();
      else if (which == 0) feedback(StatusView.Symbol.PAUSED,"選択を終了してからカメラを変更してください",this::endSelection,"選択を終了");
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

  private void previewInto(ImageView image, PhotoRepository.Photo photo, int size, java.util.function.Consumer<Boolean> complete) {
    long request = DiagnosticLog.id();
    DiagnosticLog.event("image.request", request, "burst", burstRequest,
        "photo", DiagnosticLog.photo(photo), "size", size, "format", photo.isJpeg() ? "JPEG" : "RAW");
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
      PreviewPlaceholders.get(this).load(photo, () -> !cancelled.getAsBoolean() && !load.complete,
          bitmap -> {
            image.setImageBitmap(bitmap);
            image.setStateDescription("読み込み中");
            DiagnosticLog.event("image.placeholder", request);
          });
    }
    long previous = DiagnosticLog.use(request);
    images.execute(() -> {
      if (cancelled.getAsBoolean()) { DiagnosticLog.event("image.discard", request); return; }
      try {
        Bitmap bitmap = size <= 600 ? repository.thumbnail(photo, size, cancelled)
            : PhotoRepository.bitmap(repository.preview(photo, cancelled), size);
        if (cancelled.getAsBoolean()) { bitmap.recycle(); DiagnosticLog.event("image.discard", request); return; }
        thumbs.put(key, bitmap);
        DiagnosticLog.event("image.post", request);
        main.post(() -> {
          if (!isDestroyed() && !cancelled.getAsBoolean() && image.getTag() == load) {
            load.complete = true;
            if (image instanceof DiagnosticImageView) ((DiagnosticImageView) image).bound(request);
            image.setImageBitmap(bitmap);
            image.setStateDescription("表示済み");
            DiagnosticLog.event("image.bind", request);
            complete.accept(true);
          } else DiagnosticLog.event("image.discard", request);
        });
        PreviewPlaceholders.get(this).remember(photo, bitmap);
      } catch (Exception e) {
        if (cancelled.getAsBoolean()) { DiagnosticLog.event("image.discard", request); return; }
        DiagnosticLog.event("image.fail", request, "error", e.getClass().getSimpleName());
        main.post(() -> {
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

  private void burstDetail() {
    DiagnosticLog.event("screen.burstDetail", burstRequest);
    grid = null;
    burstPosition = Math.max(0, Math.min(burstPosition, burstMembers.size() - 1));
    LinearLayout header = row();
    header.setPadding(dp(20), dp(40), dp(20), dp(16));

    LinearLayout heading = vertical();
    TextView title=label("連写 "+burstMembers.size()+"枚",20,TEXT);
    title.setAccessibilityHeading(true);title.setIncludeFontPadding(false);title.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));title.setMinHeight(dp(28));heading.addView(title);
    subtitle = label("", 12, MUTED);
    header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));

    FrameLayout stage = new FrameLayout(this);
    stage.setBackgroundColor(Color.BLACK);
    ImageView hero = new DiagnosticImageView(this);
    hero.setScaleType(ImageView.ScaleType.FIT_CENTER);
    stage.addView(hero, new FrameLayout.LayoutParams(-1, -1));
    TextView rank = label("",12,Color.WHITE);rank.setGravity(Gravity.CENTER);rank.setIncludeFontPadding(false);rank.setPadding(dp(10),0,dp(10),0);
    TextView rankState=label("",13,MUTED);rankState.setVisibility(View.GONE);heading.addView(rankState);
    FrameLayout.LayoutParams badge = new FrameLayout.LayoutParams(-2, dp(28), Gravity.TOP | Gravity.START);
    badge.setMargins(dp(12), dp(12), dp(12), 0);
    stage.addView(rank, badge);
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
    loading.collapseWhenIdle();loading.asCompact();heading.addView(loading);
    Button retry = button("再試行", () -> {});
    final Runnable[] selection = new Runnable[1];
    BurstPreviews previews = new BurstPreviews(this, hero, stage, strip, loading, retry,
        members, this::previewInto, () -> screenGeneration == generation && !isDestroyed(),
        position -> { burstPosition = position; selection[0].run(); });
    Runnable update = () -> {
      DiagnosticLog.event("burst.select", burstRequest, "position", burstPosition);
      int member = burstMembers.get(burstPosition);
      PhotoRepository.Photo photo = photos.get(member);
      previews.select(burstPosition);
      hero.setContentDescription(photo.name + "、タップで写真を拡大");
      int bestPosition=0;
      String rankNumber="",rankDescription="順位が未決定";
      StatusView.Symbol rankSymbol=StatusView.Symbol.UNKNOWN;
      try {
        PhotoRanking saved=rankingFor(burstMembers);
        if(saved.done()){for(int i=0;i<burstMembers.size();i++)if(saved.best().contains(ArchiveStore.key(photos.get(burstMembers.get(i))))){bestPosition=i+1;break;}rankNumber=saved.rankOf(ArchiveStore.key(photo))+"位";rankDescription=rankNumber+"、順位決定済み";rankSymbol=StatusView.Symbol.COMPLETE;}
        else if(saved.decisions()>0){rankDescription="順位決めの途中";rankSymbol=StatusView.Symbol.PAUSED;}
      }catch(Exception e){rankDescription="順位を読み込めません";rankSymbol=StatusView.Symbol.FAILED;}
      rank.setVisibility(rankSymbol==StatusView.Symbol.COMPLETE?View.VISIBLE:View.GONE);
      rank.setText(rankNumber);rank.setContentDescription(rankDescription);
      boolean winner="1位".equals(rankNumber);rank.setTextColor(winner?0xff241800:Color.WHITE);
      rank.setBackground(GalleryUi.shape(this,winner?0xfff5b947:0xa8000000,14));
      rankState.setVisibility(rankSymbol==StatusView.Symbol.UNKNOWN?View.GONE:View.VISIBLE);
      RankingUi.state(rankState,rankSymbol,bestPosition);
      rankState.setContentDescription(rankSymbol==StatusView.Symbol.COMPLETE?"順位決定済み、1位は"+bestPosition+"枚目":rankDescription);
      heading.setContentDescription("連写 "+burstMembers.size()+"枚、"+rankDescription);
    };
    selection[0] = update;
    stripScroll.addView(strip);
    LinearLayout controls = vertical();
    controls.setPadding(dp(16), dp(8), dp(16), dp(20));
    controls.addView(stripScroll); controls.addView(retry);
    Button sort = GalleryUi.neutralPrimary(button(sortLabel(), this::openRanking));
    sort.setElevation(0);sort.setStateListAnimator(null);
    RankingUi.add(controls,sort,8);
    Button best = RankingUi.action(button("この写真を1位に", this::chooseCurrentBest),false,48,14,null);
    RankingUi.inline(best,RankingUi.star(this),"1位にする",18);best.setTooltipText(best.getContentDescription());
    Button share=RankingUi.action(button("共有", () -> {
      List<PhotoRepository.Photo> selected = new ArrayList<>();
      for (int member : burstMembers) selected.add(photos.get(member));
      shareMenu(selected);
    }),false,48,14,"共有");
    RankingUi.add(controls,RankingUi.pair(this,best,share),8);
    RankingUi.addDock(controls,RankingUi.dock(this,
        button("前の写真", () -> { burstPosition = (burstPosition + burstMembers.size() - 1) % burstMembers.size(); update.run(); }),
        button("次の写真", () -> { burstPosition = (burstPosition + 1) % burstMembers.size(); update.run(); }),
        button("拡大", () -> showPhoto(burstMembers.get(burstPosition))),
        button("選択", () -> { selecting = true; bestOnly = true; gallery(); }),button("その他",this::photoMenu)),8);
    root.addView(new RankingUi.Screen(this,header,stage,controls,true),new LinearLayout.LayoutParams(-1,0,1));
    update.run();
  }

  private void chooseCurrentBest() {
    new GalleryUi.Sheet(this).setTitle("この写真を1位にしますか？")
        .setMessage("この連写の結果を1位と残り同率2位で保存します。")
        .setPositiveButton("1位にする", (d, w) -> {
          try {
            PhotoRanking saved = rankingFor(burstMembers);
            String winner = ArchiveStore.key(photos.get(burstMembers.get(burstPosition)));
            PhotoRanking next = new PhotoRanking(saved.ids, "B:");
            while (!next.done()) next = next.choose(next.upper().equals(winner));
            if (!rankingStore.save(next)) { feedback(StatusView.Symbol.FAILED,"順位を保存できませんでした",this::chooseCurrentBest,"1位にする"); return; }
            rankCache.clear();
            gallery();
          } catch (Exception e) { feedback(StatusView.Symbol.FAILED,"順位を保存できませんでした",this::chooseCurrentBest,"1位にする"); }
        }).setNegativeButton("キャンセル", null).show();
  }

  private PhotoRanking rankingFor(List<Integer> members) throws IOException {
    PhotoRanking cached = rankCache.get(members);
    if (cached != null) return cached;
    List<String> ids = new ArrayList<>();
    for (int i : members) ids.add(ArchiveStore.key(photos.get(i)));
    PhotoRanking saved = rankingStore.load(ids);
    rankCache.put(List.copyOf(members), saved);
    return saved;
  }

  private static final class SelectionResult {
    final List<PhotoRepository.Photo> photos = new ArrayList<>();
    final List<List<Integer>> unresolved = new ArrayList<>();
  }

  private SelectionResult resolveSelection() {
    Set<Integer> requested = new LinkedHashSet<>();
    for (int i = 0; i < photos.size(); i++)
      if (selectedPhotos.contains(photos.get(i).uri.toString())) requested.add(i);
    BestShotSelection resolved =
        BestShotSelection.resolve(
            requested,
            bundles,
            bestOnly,
            group -> {
              try {
                PhotoRanking r = rankingFor(group);
                if (!r.done()) return null;
                Set<String> best = new HashSet<>(r.best());
                Set<Integer> winners = new HashSet<>();
                for (int member : group)
                  if (best.contains(ArchiveStore.key(photos.get(member)))) winners.add(member);
                return winners;
              } catch (IllegalArgumentException | IOException e) {
                return null;
              }
            });
    SelectionResult result = new SelectionResult();
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
    List<Integer> members = displayed.get(pos);
    boolean remove = tileSelected(members);
    for (int member : members) {
      String key = photos.get(member).uri.toString();
      if (remove) selectedPhotos.remove(key); else selectedPhotos.add(key);
    }
    adapter.notifyDataSetChanged();
    updateSelection();
  }

  private boolean allVisibleSelected() {
    if (displayed.isEmpty()) return false;
    for (List<Integer> members : displayed) if (!tileSelected(members)) return false;
    return true;
  }

  private String sortLabel() {
    try {
      PhotoRanking saved = rankingFor(burstMembers);
      return saved.done() ? "ソート結果" : saved.decisions() > 0 ? "ソートを再開" : "ソート";
    } catch (Exception e) { return "ソート"; }
  }

  private void addRankFilters() {
    PhotoListUi.Filters chips = new PhotoListUi.Filters(this);
    String[] names = {"すべて", "1位", "順位が未決定", "順位決定済み"};
    for (int i = 0; i < names.length; i++) {
      final int choice = i;
      Button chip = PhotoListUi.chip(this, names[i], rankFilter == i, () -> {
        if (photos.isEmpty()) return;
        rememberGallery();
        rankFilter = choice;
        gallery();
      });
      chips.addView(chip, new ViewGroup.LayoutParams(-2,-2));
    }
    root.addView(chips);
  }

  private void rebuildDisplayed() {
    photoBundles.clear();
    for (List<Integer> group : bundles) for (int member : group) photoBundles.put(member, group);
    displayed = new ArrayList<>();
    List<List<Integer>> source = burstMembers == null ? bundles : List.of(burstMembers);
    for (List<Integer> members : source) {
      if (rankFilter == 0) {
        if (burstMembers == null) displayed.add(members);
        else for (int member : members) displayed.add(List.of(member));
        continue;
      }
      if (members.size() == 1) {
        if (rankFilter == 1) displayed.add(members);
        continue;
      }
      try {
        PhotoRanking saved = rankingFor(members);
        if (rankFilter == 1 && saved.done()) {
          Set<String> winners = new HashSet<>(saved.best());
          for (int member : members)
            if (winners.contains(ArchiveStore.key(photos.get(member)))) displayed.add(List.of(member));
        } else if ((rankFilter == 2 && !saved.done()) || (rankFilter == 3 && saved.done() && members.size() > 1)) {
          if (burstMembers == null) displayed.add(members);
          else for (int member : members) displayed.add(List.of(member));
        }
      } catch (Exception e) {
        // Unreadable results must not appear as confirmed winners.
        if (rankFilter == 2) displayed.add(members);
      }
    }
  }

  private void selectionControls() {
    DiagnosticLog.event("screen.selectionControls", 0);
    selectionCount = new SelectionUi.Summary(this);
    root.addView(selectionCount);
    addRankFilters();
    View filters=root.getChildAt(root.getChildCount()-1);
    LinearLayout.LayoutParams filterParams=new LinearLayout.LayoutParams(-1,-2);filterParams.leftMargin=-dp(16);filterParams.rightMargin=-dp(16);filters.setLayoutParams(filterParams);
    SelectionUi.BestSwitch check=new SelectionUi.BestSwitch(this);check.setChecked(bestOnly);check.setOnClickListener(GalleryUi::feedback);check.setOnCheckedChangeListener((v,enabled)->{bestOnly=enabled;updateSelection();});
    root.addView(check,new LinearLayout.LayoutParams(-1,-2));
    selectionShare = SelectionUi.action(this,"共有",true,this::shareSelection);
    selectionSort = SelectionUi.action(this,"ソート",false,this::shareSelection);
    LinearLayout actions=row();actions.addView(selectionSort,new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams shareParams=new LinearLayout.LayoutParams(0,-2,1);shareParams.leftMargin=dp(8);actions.addView(selectionShare,shareParams);root.addView(actions);
    selectionAll =
        TagDetailUi.dockButton(this,
            "すべて選択",
            () -> {
              boolean remove = allVisibleSelected();
              for (List<Integer> members : displayed) for (int member : members) {
                String key = photos.get(member).uri.toString();
                if (remove) selectedPhotos.remove(key); else selectedPhotos.add(key);
              }
              adapter.notifyDataSetChanged();
              updateSelection();
            });
    SelectionUi.allIcon(selectionAll);
    root.addView(TagDetailUi.dock(this,TagDetailUi.dockButton(this,"選択を終了",this::endSelection),selectionAll));
    updateSelection();
  }

  private void updateSelection() {
    if (!selecting || selectionCount == null) return;
    SelectionResult result = resolveSelection();
    subtitle.setText(selectedPhotos.size() + "枚選択");
    subtitle.setVisibility(selectedPhotos.isEmpty()?View.GONE:View.VISIBLE);
    Set<String> visible = new HashSet<>();
    for (List<Integer> members : displayed) for (int member : members)
      visible.add(photos.get(member).uri.toString());
    int hidden = 0;
    for (String key : selectedPhotos) if (!visible.contains(key)) hidden++;
    selectionCount.counts(result.photos.size(),hidden,result.unresolved.size(),!selectedPhotos.isEmpty());
    if(selectionTitle!=null)selectionTitle.setText(selectedPhotos.isEmpty()?"写真を選択":burstMembers!=null?"連写":albumFolder==null?"すべての写真":new File(albumFolder).getName());
    selectionSort.setEnabled(!result.unresolved.isEmpty());
    selectionShare.setEnabled(!result.photos.isEmpty() || !result.unresolved.isEmpty());
    String allLabel =
        allVisibleSelected()
            ? "選択を解除"
            : "すべて選択";
    selectionAll.setText(allLabel);
    selectionAll.setContentDescription(allVisibleSelected()?"表示中の写真の選択を解除":"表示中の写真をすべて選択");
    selectionAll.setTooltipText(allLabel);
  }

  private void endSelection() {
    selecting = false;
    selectedPhotos.clear();
    selectionCount = null;
    selectionShare = null;
    gallery();
  }

  private void shareSelection() {
    DiagnosticLog.event("screen.shareSelection", 0);
    SelectionResult result = resolveSelection();
    if (!result.unresolved.isEmpty()) {
      shareDialog = new ShareUi.Confirmation(this,result.unresolved.size(),
          () -> {
            selectionBurst = burstMembers == null ? null : new ArrayList<>(burstMembers);
            rankingFromSelection = true;
            burstMembers = new ArrayList<>(result.unresolved.get(0));
            openRanking();
          },result.photos.isEmpty()?null:() -> shareMenu(result.photos));
      shareDialog.show();
    } else if (!result.photos.isEmpty()) shareMenu(result.photos);
  }

  private void leaveRanking() {
    if (rankingFromSelection) {
      burstMembers = selectionBurst;
      rankingFromSelection = false;
    }
    gallery();
  }

  private void chooseFilter() {
    String[] values = {"すべて", "GR", "OM-1", "E-P7", "Leitz", "その他"};
    new GalleryUi.Sheet(this)
        .setTitle("カメラで絞り込み")
        .setSingleChoiceItems(
            values,
            Arrays.asList(values).indexOf(filter),
            (d, which) -> {
              d.dismiss();
              rememberGallery();
              filter = values[which];
              rankCache.clear();
              photos = new ArrayList<>();
              bundles = new ArrayList<>();
              burstMembers = null;
              gallery();
              refresh();
            })
        .setNegativeButton("閉じる", null)
        .show();
  }

  private void openTags(PhotoRepository.Photo photo) {
    Intent intent = new Intent(this, TagActivity.class);
    boolean albumRoot = albumHome && !detail && photo == null;
    intent.putExtra("folder", albumRoot ? null : albumFolder);
    intent.putExtra("camera", albumRoot ? "すべて" : filter);
    if (photo != null) intent.putExtra("photo", photo.uri.toString());
    startActivity(intent);
  }

  private void galleryMenu() {
    DiagnosticLog.event("screen.galleryMenu", 0);
    MoreMenuUi.show(this,"設定とその他の操作",
            new String[] {"クラウドと同期", "共有先の設定", "連写のまとめ方", "写真へのアクセス", "再読み込み", "写真のプレビュー", "処理の状況"},
            (d, which) -> {
              switch (which) {
                case 6:
                  startActivity(new Intent(this, DashboardActivity.class));
                  break;
                case 5:
                  new GalleryUi.Sheet(this).setTitle("写真のプレビュー")
                      .setView(preparationStatus())
                      .setPositiveButton("もう一度準備する", (dialog, w) -> {
                        PreviewPreparationJob.schedule(this);
                        feedback(StatusView.Symbol.PAUSED,"Loupeを閉じると準備を再開します",null,"");
                      }).setNegativeButton("閉じる", null).show();
                  break;
                case 0:
                  startActivity(new Intent(this, CloudActivity.class));
                  break;
                case 1:
                  editTargets();
                  break;
                case 2:
                  new GalleryUi.Sheet(this)
                      .setTitle("同じ機種・保存先の撮影間隔")
                      .setSingleChoiceItems(
                          new String[] {"まとめない", "１秒以内（標準）", "２秒以内", "３秒以内"},
                          getSharedPreferences("settings", 0).getInt("burstGapSeconds", 1),
                          (dialog, choice) -> {
                            dialog.dismiss();
                            getSharedPreferences("settings", 0)
                                .edit()
                                .putInt("burstGapSeconds", choice)
                                .apply();
                            burstMembers = null;
                            refreshIndex(true);
                          })
                      .setNegativeButton("閉じる", null)
                      .show();
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

  private void adjacentPhoto(int direction) {
    if (burstMembers == null) {
      showPhoto(index + direction);
      return;
    }
    int next = burstMembers.indexOf(index) + direction;
    if (next >= 0 && next < burstMembers.size()) showPhoto(burstMembers.get(next));
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
    subtitle=new TextView(this);listStatus=new StatusView(this);listStatus.begin("アルバムを読み込んでいます");
    addRecentPhotos();
    root.addView(AlbumListUi.heading(this,subtitle,listStatus));
    collectListHeader();
    body = vertical();
    root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
    dock(button("タグ", () -> openTags(null)), button("その他", this::galleryMenu));
    root.addView(new LibraryTabs(this, true, true, this::switchLibraryTab));
    loadAlbums();
  }

  private void collectListHeader() {
    listHeader = vertical();
    while (root.getChildCount() > 0) {
      View child = root.getChildAt(0); root.removeViewAt(0); listHeader.addView(child);
    }
  }

  private long photoTime(PhotoRepository.Photo photo) {
    return photo.taken > 0 ? photo.taken : photo.added > 0 ? photo.added
        : photo.created > 0 ? photo.created : photo.modified * 1000;
  }

  private LinearLayout carousel(String title, int height) {
    return GalleryCarouselUi.section(this,root,title,title.equals("最近の写真"));
  }

  private ImageView carouselPhoto(LinearLayout cards, PhotoRepository.Photo photo, String name, int width, Runnable open) {
    ImageView image=GalleryCarouselUi.photo(this,cards,name,width==300,open);
    final boolean[] requested = {false};
    Runnable loadVisible = () -> {
      android.graphics.Rect visible = new android.graphics.Rect();
      if (!requested[0] && image.isShown() && image.getLocalVisibleRect(visible)) {
        requested[0] = true; previewInto(image, photo, Math.min(600, width * 3));
      }
    };
    android.view.ViewTreeObserver.OnScrollChangedListener onScroll = loadVisible::run;
    image.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
      public void onViewAttachedToWindow(View view) {
        view.getViewTreeObserver().addOnScrollChangedListener(onScroll); view.post(loadVisible);
      }
      public void onViewDetachedFromWindow(View view) {
        if (view.getViewTreeObserver().isAlive()) view.getViewTreeObserver().removeOnScrollChangedListener(onScroll);
      }
    });
    if (image.isAttachedToWindow()) image.getViewTreeObserver().addOnScrollChangedListener(onScroll);
    image.addOnLayoutChangeListener((v, l, t, r, bottom, ol, ot, or, ob) -> loadVisible.run());
    image.post(loadVisible); return image;
  }

  private void addRecentPhotos() {
    LinearLayout cards = carousel("最近の写真", 156);
    int token = generation;
    scans.execute(() -> {
      try (GalleryIndex catalog = new GalleryIndex(this)) {
        List<PhotoRepository.Photo> recent = catalog.recent(20);
        main.post(() -> {
          if (isDestroyed() || token != generation || !albumHome) return;
          if (recent.isEmpty()) ((View) cards.getParent().getParent()).setVisibility(View.GONE);
          else {TextView date=((View)cards.getParent().getParent()).findViewWithTag("carousel-date");long time=photoTime(recent.get(0));date.setText(time>0?new java.text.SimpleDateFormat("M月d日",java.util.Locale.JAPAN).format(new java.util.Date(time)):"");}
          for (int n = 0; n < recent.size(); n++) {
            final int position = n; PhotoRepository.Photo photo = recent.get(n);
            long time = photoTime(photo);
            String caption = time > 0 ? new java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.JAPAN)
                .format(new java.util.Date(time)) : "撮影日時不明";
            carouselPhoto(cards, photo, caption, 300, () -> {
              photos = new ArrayList<>(recent); bundles = new ArrayList<>();
              burstMembers = null; recentPhoto = true; showPhoto(position);
            });
          }
        });
      } catch (Exception ignored) { main.post(() -> { if (token == generation) ((View) cards.getParent().getParent()).setVisibility(View.GONE); }); }
    });
  }

  private void addOtherAlbums() {
    LinearLayout cards = carousel("ほかのアルバム", 82);
    int token = generation;
    scans.execute(() -> {
      try (GalleryIndex catalog = new GalleryIndex(this)) {
        List<GalleryIndex.Album> albums = catalog.albums();
        albums.sort((a, b) -> Long.compare(photoTime(b.cover), photoTime(a.cover)));
        main.post(() -> {
          if (isDestroyed() || token != generation || selecting) return;
          for (GalleryIndex.Album album : albums) {
            if (java.util.Objects.equals(album.folder, albumFolder)) continue;
            carouselPhoto(cards, album.cover, album.name(), 104, () -> {
              rememberGallery();
              albumFolder = album.folder; filter = "すべて"; rankFilter = 0;
              rankCache.clear(); photos = new ArrayList<>(); bundles = new ArrayList<>();
              gallery(); loadIndexedPhotos();
            });
          }
          if (cards.getChildCount() == 0) ((View) cards.getParent().getParent()).setVisibility(View.GONE);
        });
      } catch (Exception ignored) { main.post(() -> { if (token == generation) ((View) cards.getParent().getParent()).setVisibility(View.GONE); }); }
    });
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
                  subtitle.setText(albums.size()+"件");
                  if(albums.isEmpty())listStatus.show(StatusView.Symbol.EMPTY,"写真がありません");else listStatus.finish();
                  rememberAlbums();
                  body.removeAllViews();
                  // ListView owns attached headers; update its adapter instead of reparenting one.
                  ScrollingGrid albumGrid = listHeader.getParent() instanceof ScrollingGrid
                      ? (ScrollingGrid) listHeader.getParent() : new ScrollingGrid(this);
                  if (listHeader.getParent() == null) albumGrid.addHeaderView(listHeader, null, false);
                  albumGridView = albumGrid;
                  AlbumListUi.configureGrid(this,albumGrid);
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
                          previewInto(cover, album.cover, 600, success -> {
                            if (success && !reportedCover) {
                              reportedCover = true;
                              cover.postOnAnimation(() -> android.util.Log.i("YohakuStartup",
                                  "album_cover_ms=" + (SystemClock.elapsedRealtime() - startedAt)));
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
                    listStatus.show(StatusView.Symbol.FAILED,"","アルバムを読み込めません",this::refresh,"再試行");
                });
          }
        });
  }

  private void loadIndexedPhotos() {
    DiagnosticLog.event("screen.loadIndexedPhotos", burstRequest);
    int token = generation;
    long updateVersion = indexUpdateVersion;
    if(listStatus!=null)listStatus.begin("写真の一覧を読み込んでいます");
    String folder = albumFolder, selected = filter;
    scans.execute(
        () -> {
          try (GalleryIndex catalog = new GalleryIndex(this)) {
            GalleryIndex.Snapshot snapshot = catalog.load(folder, selected);
            main.post(
                () -> {
                  if (isDestroyed()
                      || albumHome
                      || detail
                      || selecting
                      || burstMembers != null
                      || rankingScreen
                      || token != generation) return;
                  GalleryPosition position = GalleryPosition.capture(grid, photos, displayed);
                  rankCache.clear();
                  photos = snapshot.photos;
                  bundles = snapshot.bundles;
                  if (updateVersion == indexUpdateVersion) pendingIndexUpdate = false;
                  DiagnosticLog.event("screen.index_applied", 0, "count", photos.size());
                  burstMembers = null;
                  if (pendingRestore != null) {
                    Bundle restored = pendingRestore;
                    pendingRestore = null;
                    selecting = restored.getBoolean("selecting");
                    bestOnly = restored.getBoolean("bestOnly", true);
                    ArrayList<String> chosen = restored.getStringArrayList("selection");
                    if (chosen != null) selectedPhotos.addAll(chosen);
                    Set<String> available = new HashSet<>();
                    ArrayList<String> scope = restored.getStringArrayList("burst");
                    if (scope != null) burstMembers = new ArrayList<>();
                    for (int i = 0; i < photos.size(); i++) {
                      String uri = photos.get(i).uri.toString();
                      available.add(uri);
                      if (scope != null && scope.contains(uri)) burstMembers.add(i);
                    }
                    selectedPhotos.retainAll(available);
                    if (burstMembers != null && burstMembers.isEmpty()) burstMembers = null;
                    gallery();
                    return;
                  }
                  rebuildDisplayed();
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
                    listStatus.show(StatusView.Symbol.FAILED,"","一覧を読み込めませんでした",this::refresh,"再試行");
                });
          }
        });
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
                  feedback(StatusView.Symbol.FAILED,"写真の一覧を更新できませんでした。保存済みの一覧を表示しています。",this::refresh,"再読み込み");
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
    if (!pendingIndexUpdate || detail || selecting || burstMembers != null || rankingScreen) return;
    if (albumHome) loadAlbums();
    else loadIndexedPhotos();
  }

  private void requestPhotoAccess() {
    if (Build.VERSION.SDK_INT >= 34)
      requestPermissions(
          new String[] {
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
          },
          1);
    else
      requestPermissions(
          new String[] {
            Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE
          },
          1);
  }

  protected void onResume() {
    super.onResume();
    if (repository != null) {
      if (resumed) refresh();
      else {
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
        feedback(StatusView.Symbol.PERMISSION,"読み取り許可を保存できませんでした",this::chooseFolder,"写真へのアクセス");
      }
    }
  }

  private StatusView listStatus;
  private StatusView status(StatusView.Symbol kind,String numbers,String message) {
    StatusView view=new StatusView(this);view.show(kind,numbers,message,null,"");return view;
  }
  private View preparationStatus() {
    JobState state=PreviewPreparationJob.snapshot(this);
    return status(StatusView.symbol(state.kind),state.countsKnown?state.ready+"/"+state.total+" · "+state.failed:"—",(state.kind==JobState.Kind.UNKNOWN?"現在の状態は不明です。最後の記録: ":"")+PreviewPreparationJob.status(this));
  }
  private void feedback(StatusView.Symbol state,String text,Runnable action,String label) {
    StatusFeedback.show(this,state,text,action,label);
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
      ThumbnailCell cell =
          recycled instanceof ThumbnailCell
              ? (ThumbnailCell) recycled
              : new ThumbnailCell(MainActivity.this);
      cell.fixedHeight = dp(96);
      ImageView image = cell.image;
      List<Integer> members = displayed.get(position);
      int representative = members.get(0);
      if (members.size() > 1) try {
        PhotoRanking saved = rankingFor(members);
        if (saved.done()) for (int member : members)
          if (saved.best().contains(ArchiveStore.key(photos.get(member)))) { representative = member; break; }
      } catch (Exception ignored) {}
      PhotoRepository.Photo p = photos.get(representative);

      cell.setContentDescription(
          p.name
              + "、"
              + p.cameraGroup()
              + ("archive".equals(p.uri.getScheme()) ? "、クラウド" : "、端末")
              + (members.size() > 1 ? "、連写" + members.size() + "枚" : ""));
      image.setImageDrawable(null);
      cell.badges.selectionMode = selecting;
      cell.badges.photoSelection = true;
      cell.badges.selected = selecting && tileSelected(members);
      cell.badges.partial = false;
      if (selecting && !cell.badges.selected) for (int member : members)
        if (selectedPhotos.contains(photos.get(member).uri.toString())) cell.badges.partial = true;
      cell.setSelected(cell.badges.selected || cell.badges.partial);
      int inset = cell.badges.selected ? dp(10) : 0;
      cell.setBackgroundColor(cell.badges.selected?0xff1e2b42:0xff22272a);
      image.setPadding(inset, inset, inset, inset);
      if (selecting)
        cell.setContentDescription(
            cell.getContentDescription() + (cell.badges.selected ? "、選択済み" : cell.badges.partial ? "、一部選択" : "、未選択"));
      cell.badges.camera = "";
      cell.badges.cloud = "archive".equals(p.uri.getScheme());
      cell.badges.count = members.size();
      cell.badges.rank = "";cell.badges.rankFailed=false;
      List<Integer> group = photoBundles.get(members.get(0));
      if (group != null && group.size() > 1) {
        try {
          PhotoRanking saved = rankingFor(group);
          cell.badges.rank = saved.done()
              ? (members.size() > 1 ? "1位" : saved.rankOf(ArchiveStore.key(p)) + "位")
              : "";
        } catch (Exception e) { cell.badges.rankFailed=true;cell.setContentDescription(cell.getContentDescription()+"、順位を読み込めませんでした"); }
      }
      if (!cell.badges.rank.isEmpty())
        cell.setContentDescription(cell.getContentDescription() + "、" + cell.badges.rank);
      cell.badges.failed = false;
      cell.badges.invalidate();
      previewInto(image, p, 400, success -> {
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

  private boolean returnToRanking, recentPhoto;

  private void showPhoto(int position, boolean fromRanking) {
    DiagnosticLog.event("screen.photo", 0, "position", position);
    rememberGallery();
    if (position < 0 || position >= photos.size()) return;
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
    PhotoViewerUi viewer=new PhotoViewerUi(this,photo);
    root.addView(viewer,new LinearLayout.LayoutParams(-1,0,1));
    FrameLayout photoFrame=viewer.stage;
    ZoomImageView image=viewer.image;
    image.setPageListener(direction->{if(!fromRanking)adjacentPhoto(direction);});
    StatusView loadStatus=new StatusView(this);
    loadStatus.asPhotoOverlay();viewer.status(loadStatus);
    loadStatus.begin("元のファイルを読み込んでいます");
    TextView model = label("", 16, TEXT), dimensions = label(photo.name, 11, MUTED);
    if(!fromRanking){
      int within=burstMembers==null?position:burstMembers.indexOf(position);
      int count=burstMembers==null?photos.size():burstMembers.size();
      Button previous=viewer.action("前の写真","前へ","前の写真",()->adjacentPhoto(-1));
      Button next=viewer.action("次の写真","次へ","次の写真",()->adjacentPhoto(1));
      previous.setEnabled(within>0);next.setEnabled(within+1<count);
    }
    viewer.zoom();
    PhotoEditor.add(this,viewer,photo,actions);
    viewer.action("共有","共有","共有",()->shareMenu(photo));
    if(!fromRanking&&"content".equals(photo.uri.getScheme()))viewer.action("タグ","タグ","タグ",()->openTags(photo));
    viewer.action("情報","情報","写真の情報",()->new GalleryUi.Sheet(this).setTitle(photo.name)
        .setMessage(model.getText()+"\n"+dimensions.getText()+"\n"+photo.folder)
        .setPositiveButton("閉じる",null).show());
    if(fromRanking&&!ranking.done())viewer.choose(()->saveRanking(ranking.choose(rankingMembers.get(ranking.upper())==position)));
    viewing =
        actions.submit(
            () -> {
              try {
                PhotoRepository.Preview preview = repository.preview(photo);
                Bitmap bitmap = PhotoRepository.bitmap(preview, 1600);
                main.post(
                    () -> {
                      if (isDestroyed() || !detail || token != generation) {
                        bitmap.recycle();
                        return;
                      }
                      activePreview = preview;
                      image.setBitmap(bitmap);
                      model.setText(preview.model);
                      dimensions.setText(photo.name);
                      loadStatus.companion(StatusView.Symbol.PREVIEW);viewer.refreshStatus();
                      image.setStateDescription("プレビューを表示しています。元のファイルを読み込んでいます");
                    });
              } catch (Exception missingPreview) {
                main.post(
                    () -> {
                      if (!isDestroyed() && detail && token == generation)
                        model.setText("");
                    });
              }
              try {
                if (Thread.currentThread().isInterrupted()
                    || isDestroyed()
                    || !detail
                    || token != generation) return;
                PhotoRepository.Photo original = OriginalCache.obtain(this, photo);
                PhotoRepository.Preview full = repository.fullPreview(original);
                Bitmap high = PhotoRepository.bitmap(full, 4000);
                main.post(
                    () -> {
                      if (isDestroyed() || !detail || token != generation) {
                        high.recycle();
                        return;
                      }
                      loadStatus.companion(null);loadStatus.finish();viewer.refreshStatus();
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
                    });
              } catch (Exception unavailable) {
                main.post(
                    () -> {
                      if (!isDestroyed() && detail && token == generation) {
                        String failure=activePreview==null?"写真を読み込めませんでした":"プレビューを表示しています。元のファイルを読み込めませんでした";
                        if(activePreview==null){
                          viewer.failed();
                          photoFrame.removeView(loadStatus);
                          LinearLayout recovery=new LinearLayout(this);recovery.setOrientation(LinearLayout.VERTICAL);recovery.setGravity(Gravity.CENTER);
                          loadStatus.asPhotoFailure();loadStatus.show(StatusView.Symbol.FAILED,failure);recovery.addView(loadStatus,new LinearLayout.LayoutParams(dp(32),dp(32)));
                          Button retry=button("再試行",()->showPhoto(position,fromRanking));retry.setContentDescription(failure+"。再試行");retry.setTextSize(14);retry.setPadding(dp(18),0,dp(18),0);
                          android.graphics.drawable.GradientDrawable outline=GalleryUi.shape(this,Color.TRANSPARENT,24);outline.setStroke(dp(1),0xff3a3a3f);retry.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33c7d8e4),outline,GalleryUi.shape(this,Color.WHITE,24)));
                          ActionIcon retryIcon=new ActionIcon("再試行",getResources().getDisplayMetrics().density*20/26);retryIcon.setBounds(0,0,dp(20),dp(20));retry.setCompoundDrawables(retryIcon,null,null,null);retry.setCompoundDrawablePadding(dp(6));recovery.addView(retry,new LinearLayout.LayoutParams(-2,dp(48)));
                          android.graphics.drawable.GradientDrawable retryBackground=GalleryUi.shape(this,Color.BLACK,24);retryBackground.setStroke(dp(1),0xff3a3a3f);retry.setBackground(retryBackground);
                          LinearLayout.LayoutParams retryPosition=(LinearLayout.LayoutParams)retry.getLayoutParams();retryPosition.topMargin=dp(12);retry.setLayoutParams(retryPosition);
                          photoFrame.addView(recovery,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
                        }else loadStatus.show(StatusView.Symbol.FAILED,"",failure,()->showPhoto(position,fromRanking),"再試行");
                        viewer.refreshStatus();
                        image.setStateDescription(failure);
                        if(activePreview==null)model.setText("");
                        dimensions.setText(photo.name);
                      }
                    });
              }
            });
  }

  public void onBackPressed() {
    DiagnosticLog.event("action.back", 0, "screen", "MainActivity");
    if (rankingScreen) leaveRanking();
    else if (selecting && !detail) endSelection();
    else if (detail) {
      if (returnToRanking) showRanking();
      else if (recentPhoto) { recentPhoto = false; albumHome(); }
      else gallery();
    } else if (burstMembers != null) {
      burstMembers = null;
      gallery();
    } else if (!albumHome && albumFolder != null) returnLibraryRoot();
    else super.onBackPressed();
  }

  private void openRanking() {
    DiagnosticLog.event("screen.openRanking", 0);
    rankingResultState = null;
    rankingResultFocus = null;
    try {
      rankingMembers.clear();
      for (int member : burstMembers)
        rankingMembers.put(ArchiveStore.key(photos.get(member)), member);
      List<String> ids = new ArrayList<>(rankingMembers.keySet());
      ranking = rankingStore.load(ids);
      if (ranking.journal.isEmpty()) ranking = new PhotoRanking(ranking.ids, "B:");
      showRanking();
    } catch (Exception e) {
      feedback(StatusView.Symbol.FAILED,"順位の記録を読み込めませんでした。保存済みの記録は変更していません。",this::openRanking,"再試行");
    }
  }

  private void saveRanking(PhotoRanking next) {
    try {
      if (rankingStore.save(next)) {
        rankCache.clear();
        ranking = next;
        rankingResultState = null;
    rankingResultFocus = null;
        showRanking();
        return;
      }
    } catch (IOException e) {
      // Keep the displayed comparison unchanged when the journal cannot be saved.
    }
    feedback(StatusView.Symbol.STORAGE,"順位を保存できませんでした。空き容量を確認してください。",this::showRanking,"ソートに戻る");
  }

  private void showRanking() {
    DiagnosticLog.event("screen.showRanking", 0);
    rememberGallery();
    if (viewing != null) viewing.cancel(true);
    detail = true;
    rankingScreen = true;
    int token = ++generation;
    base();
    if (ranking.done()) { showRankingResult(token); return; }
    LinearLayout top = vertical();
    top.setGravity(Gravity.CENTER_HORIZONTAL|Gravity.BOTTOM);
    top.setPadding(dp(16),0,dp(16),0);top.setMinimumHeight(dp(52));

    TextView sortHeading = label(ranking.done() ? "ソート結果" : "ソート", 17, TEXT);
    sortHeading.setAccessibilityHeading(true);sortHeading.setGravity(Gravity.CENTER);sortHeading.setIncludeFontPadding(false);sortHeading.setMinHeight(dp(22));sortHeading.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));
    top.addView(sortHeading);
    TextView comparisonCount=label("比較 "+(ranking.decisions()+1)+"回目",12,MUTED);comparisonCount.setGravity(Gravity.CENTER);comparisonCount.setIncludeFontPadding(false);comparisonCount.setMinHeight(dp(16));top.addView(comparisonCount);
    LinearLayout modes = RankingModes.create(this, ranking.bestOnly(),
        () -> changeRankingMode(true), () -> changeRankingMode(false));

    Button undo = button("直前の操作を取り消す", () -> saveRanking(ranking.undo()));
    undo.setText("取り消し");
    undo.setEnabled(ranking.decisions() > 0);
    Button chooseA = GalleryUi.neutralPrimary(button("Aを選ぶ", () -> saveRanking(ranking.choose(true))));
    Button chooseB = GalleryUi.neutralPrimary(button("Bを選ぶ", () -> saveRanking(ranking.choose(false))));
    chooseA.setEnabled(false); chooseB.setEnabled(false);
    Button tie = button("同じ順位", () -> saveRanking(ranking.tie()));
    tie.setEnabled(false);


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
            tie.setEnabled(!ranking.bestOnly());
            chooseA.setEnabled(true); chooseB.setEnabled(true);
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

    LinearLayout controls=vertical();controls.setPadding(dp(16),dp(10),dp(16),dp(20));
    RankingUi.action(chooseA,true,52,15,null);RankingUi.action(chooseB,true,52,15,null);
    RankingUi.choice(chooseA,"A");RankingUi.choice(chooseB,"B");
    RankingUi.add(controls,RankingUi.pair(this,chooseA,chooseB),0);
    Button zoomA=RankingUi.action(button("Aを拡大",()->showRankedPhoto(rankingMembers.get(ranking.upper()))),false,48,13,"拡大");
    Button zoomB=RankingUi.action(button("Bを拡大",()->showRankedPhoto(rankingMembers.get(ranking.lower()))),false,48,13,"拡大");
    RankingUi.action(tie,false,48,13,"同じ順位");
    RankingUi.add(controls,RankingUi.zooms(this,zoomA,ranking.bestOnly()?null:tie,zoomB),8);
    modes.setPadding(0,0,0,0);modes.setBackgroundColor(RankingUi.BG);
    RankingUi.add(controls,modes,8);
    RankingUi.addDock(controls,RankingUi.dock(this,undo,button("その他",this::rankingMenu)),8);
    root.addView(new RankingUi.Screen(this,top,comparison,controls,false),new LinearLayout.LayoutParams(-1,0,1));
  }

  private void showRankingResult(int token) {
    ListView results=new ListView(this);results.setDivider(null);results.setDividerHeight(0);results.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));results.setItemsCanFocus(true);
    results.addHeaderView(RankingResultUi.heading(this,ranking.ids.size(),ranking.bestOnly()),null,false);
    results.setAdapter(new android.widget.BaseAdapter(){
      public int getCount(){return 1;} public Object getItem(int p){return ranking;} public long getItemId(int p){return p;} public boolean isEnabled(int p){return false;}
      public View getView(int p,View recycled,ViewGroup parent){
        return RankingResultUi.body(MainActivity.this,ranking,id->photos.get(rankingMembers.get(id)).name,
          (id,image,failed)->{PhotoRepository.Photo photo=photos.get(rankingMembers.get(id));int edge=Math.max(1,image.getWidth());images.execute(()->{if(isDestroyed()||token!=generation)return;try{PhotoRepository.Preview preview=repository.preview(photo,()->isDestroyed()||token!=generation);if(isDestroyed()||token!=generation)return;Bitmap bitmap=PhotoRepository.bitmap(preview,edge);main.post(()->{if(!isDestroyed()&&token==generation)image.setImageBitmap(bitmap);else bitmap.recycle();});}catch(Exception e){main.post(()->{if(!isDestroyed()&&token==generation)failed.run();});}});},
          (id,target)->{rankingResultState=results.onSaveInstanceState();rankingResultPosition=results.getFirstVisiblePosition();rankingResultOffset=results.getChildAt(0)==null?0:results.getChildAt(0).getTop();rankingResultFocus=(String)target.getTag();rankingResultInputFocus=target.hasFocus();rankingResultAccessibilityFocus=target.isAccessibilityFocused();showRankedPhoto(rankingMembers.get(id));},MainActivity.this::confirmResort);
      }
    });
    root.addView(results,new LinearLayout.LayoutParams(-1,0,1));
    if(rankingResultState!=null)results.onRestoreInstanceState(rankingResultState);
    results.post(()->{if(rankingResultState!=null){results.setSelectionFromTop(rankingResultPosition,rankingResultOffset);results.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener(){public boolean onPreDraw(){results.getViewTreeObserver().removeOnPreDrawListener(this);View target=rankingResultFocus==null?null:results.findViewWithTag(rankingResultFocus);if(target!=null){if(rankingResultInputFocus)target.requestFocus();if(rankingResultAccessibilityFocus)target.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,null);}return true;}});}});
    int first=root.getChildCount();LinearLayout controls=vertical();
    Button share=TagDetailUi.action(this,"共有",true,()->{selecting=true;bestOnly=true;leaveRanking();});share.setCompoundDrawables(null,null,null,null);controls.addView(share,new LinearLayout.LayoutParams(-1,-2));
    Button mode=TagDetailUi.action(this,ranking.bestOnly()?"すべての順位を決める":"1位を決める",false,()->changeRankingMode(!ranking.bestOnly()));mode.setText(ranking.bestOnly()?"すべての順位を決める":"1位を決める");controls.addView(mode,new LinearLayout.LayoutParams(-1,-2));
    Button undo=TagDetailUi.dockButton(this,"直前の操作を取り消す",()->saveRanking(ranking.undo()));undo.setText("取り消し");undo.setEnabled(ranking.decisions()>0);controls.addView(TagDetailUi.dock(this,undo));TagDetailUi.gaps(controls);root.addView(controls);TagDetailUi.footer(root,first);
  }

  private void confirmResort() {
    new GalleryUi.Sheet(this).setTitle("この連写を再ソートしますか？")
      .setMessage("この連写の選択履歴を消して、最初から比較します。写真は変更しません。")
      .setPositiveButton("再ソート",(dialog,which)->saveRanking(new PhotoRanking(ranking.ids,ranking.bestOnly()?"B:":"A:")))
      .setNegativeButton("キャンセル",null).show();
  }

  private void changeRankingMode(boolean best) {
    if (ranking.bestOnly() == best) return;
    new GalleryUi.Sheet(this).setTitle("ソート方式を変更しますか？")
        .setMessage("この連写の結果と途中の判断を置き換え、最初から比較します。")
        .setPositiveButton("変更", (d, w) -> saveRanking(new PhotoRanking(ranking.ids, best ? "B:" : "A:")))
        .setNegativeButton("キャンセル", null).show();
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
    ComparisonPhotoView image = new ComparisonPhotoView(this, upper ? "A" : "B", photo.name);
    image.setOnClickListener(
        v -> {
          if (rankingScreen && token == generation && ratios[0] > 0 && ratios[1] > 0) {
            GalleryUi.feedback(v);
            saveRanking(ranking.choose(upper));
          }
        });
    image.setOnLongClickListener(
        v -> {
          if (token == generation) showRankedPhoto(rankingMembers.get(key));
          return true;
        });
    image.setEnabled(false);
    candidates[upper ? 0 : 1] = image;
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
                    for (ImageView candidate : candidates) candidate.setEnabled(true);
                  }
                });
          } catch (Exception e) {
            main.post(
                () -> {
                  if (!isDestroyed() && token == generation) {
                    image.failed();
                    feedback(StatusView.Symbol.FAILED,"写真を読み込めませんでした。一覧へ戻って開き直してください。",this::leaveRanking,"一覧へ");
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
    List<PhotoRepository.Photo> frozen = List.copyOf(selection);
    Set<String> best = new HashSet<>();int cloud=0;
    for(PhotoRepository.Photo photo:frozen){
      if("archive".equals(photo.uri.getScheme()))cloud++;
      int index=photos.indexOf(photo);List<Integer> group=photoBundles.get(index);
      if(group!=null&&group.size()>1)try{PhotoRanking rank=rankingFor(group);if(rank.done()&&rank.best().contains(ArchiveStore.key(photo)))best.add(photo.uri.toString());}catch(Exception ignored){}
    }
    ShareUi.Menu menu=new ShareUi.Menu(this,frozen,ShareTargets.load(this),cloud,best,(raw,target)->export(frozen,raw,target));
    shareDialog=menu;
    menu.setOnDismissListener(d->{for(ImageView image:menu.previews)if(image.getTag() instanceof ImageLoad)((ImageLoad)image.getTag()).cancelled=true;});
    menu.show();
    for(int i=0;i<menu.previews.size();i++)previewInto(menu.previews.get(i),menu.previewPhotos.get(i),dp(52));
  }

  private void export(
      List<PhotoRepository.Photo> selection, boolean raw, ShareTargets.Target target) {
    if (shareProgress != null && shareProgress.isShowing()) return;
    final int attempt = ++shareAttempt;
    shareProgress = new ShareUi.Preparing(this,selection.size(),raw,()->{
      if(attempt!=shareAttempt)return;
      shareAttempt++;
      if(sharing!=null)sharing.cancel(true);
      feedback(StatusView.Symbol.CANCELLED,"共有の準備を中止しました",null,"");
    });
    shareProgress.show();
    sharing = actions.submit(
        () -> {
          try {
            SharePreparation.Result prepared = sharePreparation.prepare(selection, raw);
            ArrayList<Uri> uris = new ArrayList<>();
            for (File file : prepared.files)
              uris.add(Uri.parse("content://jp.nano.rawgallery.share/" + file.getName()));
            String type = prepared.mimeType;
            String action = uris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE;
            main.post(
                () -> {
                  if (isDestroyed() || attempt != shareAttempt) return;
                  shareProgress.dismiss();
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
                    ComponentName component = ComponentName.unflattenFromString(target.component);
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
                      feedback(StatusView.Symbol.FORMAT,"この共有先は選んだ画像形式に対応していません。共有先の形式を変更してください。",this::editTargets,"共有先の設定");
                      return;
                    }
                    send.setComponent(component);
                  }
                  try {
                    startActivity(target == null ? Intent.createChooser(send, "写真を共有") : send);
                  } catch (ActivityNotFoundException e) {
                    feedback(StatusView.Symbol.NO_APP,"共有先が見つかりません。共有先の設定を変更してください。",this::editTargets,"共有先の設定");
                  }
                });
          } catch (Exception e) {
            main.post(() -> {
              if (isDestroyed() || attempt != shareAttempt) return;
              shareProgress.dismiss();
              shareDialog=new ShareUi.Failure(this,selection.size(),raw,
                  selection.size()+"枚の選択は保持されています。共有を準備できませんでした。空き容量・通信・読み取り許可を確認してください。",
                  ()->export(selection,raw,target));
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
                  feedback(StatusView.Symbol.ALREADY,"登録済みです",null,"");
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

  protected void onDestroy() {
    generation++;
    shareAttempt++;
    if (shareProgress != null) shareProgress.dismiss();
    if (shareDialog != null) shareDialog.dismiss();
    getContentResolver().unregisterContentObserver(mediaObserver);
    main.removeCallbacks(mediaChanged);
    scans.shutdownNow();
    images.shutdownNow();
    actions.shutdownNow();
    super.onDestroy();
  }
}
