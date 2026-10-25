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
  private int albumScroll;
  private String shownGalleryKey;
  private final Map<String, Integer> galleryScroll = new HashMap<>();

  private void rememberGallery() {
    if (!albumHome && !detail && grid != null && shownGalleryKey != null)
      galleryScroll.put(shownGalleryKey, grid.firstCellPosition());
  }

  private boolean selecting, bestOnly = true, rankingFromSelection;
  private final Set<String> selectedPhotos = new LinkedHashSet<>();
  private int rankFilter;
  private final Map<Integer, List<Integer>> photoBundles = new HashMap<>();
  private final Map<List<Integer>, PhotoRanking> rankCache = new HashMap<>();
  private List<Integer> selectionBurst;
  private TextView selectionCount;
  private Button selectionShare, selectionAll;
  private GalleryAdapter adapter;
  private String filter = "すべて";
  private String albumFolder;
  private boolean albumHome = true, resumed;
  private final IndexRefreshRequests refreshRequests = new IndexRefreshRequests();
  private final long startedAt = SystemClock.elapsedRealtime();
  private boolean reportedAlbums, reportedCover;
  private final Runnable mediaChanged = () -> refresh();
  private final android.database.ContentObserver mediaObserver =
      new android.database.ContentObserver(main) {
        public void onChange(boolean selfChange) {
          // Bound the wait from the first notification even during a long import.
          if (!main.hasCallbacks(mediaChanged)) main.postDelayed(mediaChanged, 300);
        }
      };
  private volatile int generation = 0;
  private Future<?> viewing;
  private boolean detail = false;
  private boolean rankingScreen = false;
  private PhotoRanking ranking;
  private final Map<String, Integer> rankingMembers = new HashMap<>();
  private int index = 0;
  private PhotoRepository.Preview activePreview;
  private List<ShareTargets.Target> targets;
  private Future<?> sharing;
  private AlertDialog shareProgress;
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
    if (albumHome && albumGridView != null) albumScroll = albumGridView.firstCellPosition();
    albumHome = false;
    if (viewing != null) viewing.cancel(true);
    detail = false;
    rankingScreen = false;
    generation++;
    activePreview = null;
    base();
    if (burstMembers != null && !selecting) { burstDetail(); return; }
    LinearLayout header = row();
    header.setPadding(dp(4), dp(4), dp(4), dp(4));
    header.setMinimumHeight(dp(64));
    Button navigate = button(selecting ? "選択を終了" : "戻る", () -> {
      if (selecting) endSelection();
      else if (burstMembers != null) { burstMembers = null; gallery(); }
      else albumHome();
    });
    LinearLayout heading = vertical();
    TextView title = label(burstMembers != null ? "連写" : albumFolder == null ? "すべての写真"
        : new File(albumFolder).getName(), 18, TEXT);
    title.setMaxLines(1);
    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
    heading.addView(title);
    subtitle = label("読み込み中…", 12, MUTED);
    heading.addView(subtitle);
    header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));

    if (!selecting && burstMembers == null) addOtherAlbums();
    root.addView(header);
    collectListHeader();
    body = vertical();
    root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
    grid = new ScrollingGrid(this);
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
    grid.selectCell(galleryScroll.getOrDefault(shownGalleryKey, 0));
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
    TextView empty = label("表示する写真がありません", 14, MUTED);
    empty.setGravity(Gravity.CENTER);
    gridFrame.addView(empty, new FrameLayout.LayoutParams(-1, -1));
    grid.setEmptyView(empty);
    body.addView(gridFrame, new LinearLayout.LayoutParams(-1, -1));
    int controlsStart = root.getChildCount();
    addRankFilters();
    if (selecting) { selectionControls(); dock(navigate); }
    else dock(navigate, button("選択", () -> {
      if (!photos.isEmpty()) { selecting = true; bestOnly = true; gallery(); }
    }), button("タグ", () -> openTags(null)), button("その他", this::photoMenu));
    GalleryUi.bottomControls(root, controlsStart);
    if (selecting) updateSelection();
    else if (!photos.isEmpty()) updateCount();
  }

  private void photoMenu() {
    DiagnosticLog.event("screen.photoMenu", 0);
    new GalleryUi.Sheet(this).setItems(new String[] {"カメラで絞り込み", "タグ", "設定とその他"}, (d, which) -> {
      if (which == 0 && !selecting) chooseFilter();
      else if (which == 0) toast("選択を終了してからカメラを変更してください");
      else if (which == 1) openTags(null);
      else galleryMenu();
    }).setNegativeButton("閉じる", null).show();
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
    header.setPadding(dp(4), dp(4), dp(4), dp(4));
    header.setMinimumHeight(dp(64));

    LinearLayout heading = vertical();
    heading.addView(label("連写", 18, TEXT));
    subtitle = label(burstMembers.size() + "枚", 12, MUTED);
    heading.addView(subtitle);
    header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));

    root.addView(header);
    FrameLayout stage = new FrameLayout(this);
    ImageView hero = new DiagnosticImageView(this);
    hero.setScaleType(ImageView.ScaleType.FIT_CENTER);
    stage.addView(hero, new FrameLayout.LayoutParams(-1, -1));
    TextView rank = label("", 13, TEXT);
    rank.setPadding(dp(12), dp(6), dp(12), dp(6));
    rank.setBackground(GalleryUi.shape(this, 0xcc19191b, 16));
    FrameLayout.LayoutParams badge = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
    badge.setMargins(dp(16), dp(16), dp(16), 0);
    stage.addView(rank, badge);
    root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
    hero.setOnClickListener(v -> showPhoto(burstMembers.get(burstPosition)));
    HorizontalScrollView stripScroll = new HorizontalScrollView(this);
    stripScroll.setHorizontalScrollBarEnabled(false);
    stripScroll.setFillViewport(true);
    LinearLayout strip = row();
    strip.setGravity(Gravity.CENTER);
    strip.setPadding(dp(12), dp(16), dp(12), dp(16));
    List<PhotoRepository.Photo> members = new ArrayList<>();
    for (int member : burstMembers) members.add(photos.get(member));
    int screenGeneration = generation;
    TextView loading = label("", 12, MUTED);
    heading.addView(loading);
    Button retry = button("読み込めなかった写真を再試行", () -> {});
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
      String status = "順位が未決定";
      try {
        PhotoRanking saved = rankingFor(burstMembers);
        if (saved.done()) status = saved.rankOf(ArchiveStore.key(photo)) + "位 · 順位決定済み";
        else if (saved.decisions() > 0) status = "順位決めの途中";
      } catch (Exception e) { status = "順位を読み込めません"; }
      rank.setText(status);
      rank.setTextColor(status.contains("位") ? 0xfff5b947 : TEXT);
      subtitle.setText((burstPosition + 1) + " / " + burstMembers.size() + "枚");
    };
    selection[0] = update;
    stripScroll.addView(strip);
    int controlsStart = root.getChildCount();
    root.addView(stripScroll);
    root.addView(retry);
    LinearLayout controls = vertical();
    controls.setPadding(dp(16), dp(8), dp(16), dp(20));
    Button sort = GalleryUi.neutralPrimary(button(sortLabel(), this::openRanking));
    controls.addView(sort, new LinearLayout.LayoutParams(-1, -2));
    LinearLayout secondary = row();
    secondary.setPadding(0, dp(8), 0, 0);
    secondary.addView(button("この写真を1位に", this::chooseCurrentBest), new LinearLayout.LayoutParams(0, -2, 1));
    secondary.addView(button("共有", () -> {
      List<PhotoRepository.Photo> selected = new ArrayList<>();
      for (int member : burstMembers) selected.add(photos.get(member));
      shareMenu(selected);
    }), new LinearLayout.LayoutParams(0, -2, 1));
    controls.addView(secondary);
    root.addView(controls);
    dock(button("戻る", () -> { burstMembers = null; gallery(); }),
        button("前の写真", () -> { burstPosition = (burstPosition + burstMembers.size() - 1) % burstMembers.size(); update.run(); }),
        button("次の写真", () -> { burstPosition = (burstPosition + 1) % burstMembers.size(); update.run(); }),
        button("拡大", () -> showPhoto(burstMembers.get(burstPosition))));
    dock(button("選択", () -> { selecting = true; bestOnly = true; gallery(); }), button("その他", this::photoMenu));
    GalleryUi.bottomControls(root, controlsStart);
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
            if (!rankingStore.save(next)) { toast("順位を保存できませんでした"); return; }
            rankCache.clear();
            gallery();
          } catch (Exception e) { toast("順位を保存できませんでした"); }
        }).setNegativeButton("戻る", null).show();
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
    HorizontalScrollView scroll = new HorizontalScrollView(this);
    scroll.setHorizontalScrollBarEnabled(false);
    LinearLayout chips = row();
    chips.setPadding(dp(12), 0, dp(12), dp(8));
    String[] names = {"すべて", "1位", "順位が未決定", "順位決定済み"};
    for (int i = 0; i < names.length; i++) {
      final int choice = i;
      Button chip = GalleryUi.chip(this, names[i], rankFilter == i, () -> {
        if (photos.isEmpty()) return;
        rankFilter = choice;
        gallery();
      });
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
      lp.setMargins(0, 0, dp(8), 0);
      chips.addView(chip, lp);
    }
    scroll.addView(chips);
    root.addView(scroll);
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
    CheckBox check = new CheckBox(this);
    check.setText("連写は1位だけ共有（同率を含む）");
    check.setTextColor(TEXT);
    check.setTextSize(14);
    check.setMinHeight(dp(48));
    check.setPadding(dp(16), 0, dp(16), 0);
    check.setChecked(bestOnly);
    check.setOnClickListener(GalleryUi::feedback);
    check.setOnCheckedChangeListener(
        (v, enabled) -> {
          bestOnly = enabled;
          updateSelection();
        });
    root.addView(check);
    selectionCount = label("", 12, MUTED);
    selectionCount.setPadding(dp(16), 0, dp(16), dp(6));
    root.addView(selectionCount);
    selectionShare = GalleryUi.primary(button("共有", this::shareSelection));
    selectionAll =
        button(
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
    dock(selectionAll, selectionShare);
    updateSelection();
  }

  private void updateSelection() {
    if (!selecting || selectionCount == null) return;
    SelectionResult result = resolveSelection();
    subtitle.setText(selectedPhotos.size() + "枚選択");
    Set<String> visible = new HashSet<>();
    for (List<Integer> members : displayed) for (int member : members)
      visible.add(photos.get(member).uri.toString());
    int hidden = 0;
    for (String key : selectedPhotos) if (!visible.contains(key)) hidden++;
    selectionCount.setText(
        "対象 "
            + result.photos.size()
            + "枚"
            + (hidden > 0 ? "\n現在の絞り込みでは表示されない写真" + hidden + "枚を含みます" : "")
            + (result.unresolved.isEmpty() ? "" : " · 順位が未決定 " + result.unresolved.size() + "組"));
    selectionShare.setEnabled(!result.photos.isEmpty() || !result.unresolved.isEmpty());
    String allLabel =
        allVisibleSelected()
            ? "表示分を解除"
            : "表示分を選択";
    selectionAll.setText(allLabel);
    selectionAll.setContentDescription(allLabel);
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
      GalleryUi.Sheet sheet = new GalleryUi.Sheet(this);
      sheet
          .setTitle("順位が未決定の連写があります")
          .setMessage(result.unresolved.size() + "組の順位がまだ決まっていません。ソートした後に選択画面へ戻れます。")
          .setPositiveButton(
              "ソート",
              (d, w) -> {
                selectionBurst = burstMembers == null ? null : new ArrayList<>(burstMembers);
                rankingFromSelection = true;
                burstMembers = new ArrayList<>(result.unresolved.get(0));
                openRanking();
              })
          .setNegativeButton("戻る", null);
      if (!result.photos.isEmpty())
        sheet.setNeutralButton("順位が未決定の連写を除いて共有", (d, w) -> shareMenu(result.photos));
      sheet.show();
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
    intent.putExtra("folder", albumFolder);
    intent.putExtra("camera", filter);
    if (photo != null) intent.putExtra("photo", photo.uri.toString());
    startActivity(intent);
  }

  private void galleryMenu() {
    DiagnosticLog.event("screen.galleryMenu", 0);
    new GalleryUi.Sheet(this)
        .setTitle("設定とその他の操作")
        .setItems(
            new String[] {"クラウドと同期", "共有先の設定", "連写のまとめ方", "写真へのアクセス", "再読み込み", "写真のプレビュー"},
            (d, which) -> {
              switch (which) {
                case 5:
                  new GalleryUi.Sheet(this).setTitle("写真のプレビュー")
                      .setMessage(PreviewPreparationJob.status(this))
                      .setPositiveButton("もう一度準備する", (dialog, w) -> {
                        PreviewPreparationJob.schedule(this);
                        toast("Loupeを閉じると準備を再開します");
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
            })
        .setNegativeButton("閉じる", null)
        .show();
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
    albumHome = true;
    detail = false;
    rankingScreen = false;
    burstMembers = null;
    generation++;
    base();
    LinearLayout top = row();
    top.setPadding(dp(16), dp(8), dp(16), dp(8));
    top.setMinimumHeight(dp(48));
    top.addView(label("アルバム", 22, TEXT), new LinearLayout.LayoutParams(0, -2, 1));

    subtitle = label("読み込み中…", 12, MUTED);
    addRecentPhotos();
    root.addView(top);
    Button allPhotos = button("すべての写真  →", () -> {
      albumFolder = null; filter = "すべて"; rankFilter = 0;
      rankCache.clear(); photos = new ArrayList<>(); bundles = new ArrayList<>();
      gallery(); loadIndexedPhotos();
    });
    allPhotos.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    allPhotos.setPadding(dp(20), 0, dp(20), 0);

    subtitle.setPadding(dp(20), dp(4), dp(20), dp(8));
    root.addView(subtitle);
    collectListHeader();
    body = vertical();
    root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
    dock(allPhotos, button("その他", this::galleryMenu));
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
    LinearLayout section = vertical();
    TextView heading = label(title, 13, MUTED);
    heading.setPadding(dp(16), dp(8), dp(16), dp(4)); section.addView(heading);
    HorizontalScrollView scroll = new HorizontalScrollView(this);
    scroll.setHorizontalScrollBarEnabled(false);
    LinearLayout cards = row(); cards.setPadding(dp(16), 0, dp(8), 0);
    scroll.addView(cards); section.addView(scroll, new LinearLayout.LayoutParams(-1, dp(height)));
    root.addView(section); return cards;
  }

  private ImageView carouselPhoto(LinearLayout cards, PhotoRepository.Photo photo, String name, int width, Runnable open) {
    LinearLayout card = vertical();
    ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
    image.setBackground(GalleryUi.shape(this, PANEL, 12)); image.setClipToOutline(true);
    card.addView(image, new LinearLayout.LayoutParams(-1, 0, 1));
    TextView caption = label(name, 12, TEXT); caption.setMaxLines(1);
    caption.setEllipsize(android.text.TextUtils.TruncateAt.END); card.addView(caption);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(width), -1); lp.setMargins(0, 0, dp(8), 0);
    cards.addView(card, lp); card.setContentDescription(name); card.setOnClickListener(v -> open.run());
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
          for (int n = 0; n < recent.size(); n++) {
            final int position = n; PhotoRepository.Photo photo = recent.get(n);
            long time = photoTime(photo);
            String caption = time > 0 ? new java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.JAPAN)
                .format(new java.util.Date(time)) : "撮影日時不明";
            carouselPhoto(cards, photo, caption, 220, () -> {
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
            carouselPhoto(cards, album.cover, album.name(), 100, () -> {
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
    scans.execute(
        () -> {
          try (GalleryIndex catalog = new GalleryIndex(this)) {
            List<GalleryIndex.Album> albums = catalog.albums();
            main.post(
                () -> {
                  if (isDestroyed() || !albumHome || token != generation) return;
                  subtitle.setText(albums.isEmpty() ? "写真の一覧を準備しています…" : albums.size() + " アルバム");
                  if (albumGridView != null) albumScroll = albumGridView.firstCellPosition();
                  body.removeAllViews();
                  // ListView owns attached headers; update its adapter instead of reparenting one.
                  ScrollingGrid albumGrid = listHeader.getParent() instanceof ScrollingGrid
                      ? (ScrollingGrid) listHeader.getParent() : new ScrollingGrid(this);
                  if (listHeader.getParent() == null) albumGrid.addHeaderView(listHeader, null, false);
                  albumGridView = albumGrid;
                  albumGrid.setNumColumns(2);
                  albumGrid.setHorizontalSpacing(dp(12));
                  albumGrid.setPadding(dp(16), dp(8), dp(16), dp(16));
                  albumGrid.setClipToPadding(false);
                  albumGrid.setVerticalSpacing(dp(20));
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
                            cell = vertical();
                            ImageView cover = new DiagnosticImageView(MainActivity.this);
                            cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                            cover.setBackground(GalleryUi.shape(MainActivity.this, PANEL, 12));
                            cover.setClipToOutline(true);
                            cell.addView(cover, new LinearLayout.LayoutParams(-1, dp(160)));
                            TextView caption = label("", 15, TEXT);
                            caption.setPadding(dp(2), dp(8), dp(2), 0);
                            cell.addView(caption);
                            TextView count = label("", 13, MUTED);
                            count.setPadding(dp(2), dp(3), dp(2), 0);
                            cell.addView(count);
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
                  albumGrid.selectCell(albumScroll);
                  albumGrid.onCellClick(
                      (p, v, pos, id) -> {
                        albumFolder = albums.get(pos).folder;
                        filter = "すべて";
                        rankCache.clear();
              photos = new ArrayList<>();
                        bundles = new ArrayList<>();
                        gallery();
                        loadIndexedPhotos();
                      });
                  body.addView(albumGrid, new LinearLayout.LayoutParams(-1, -1));
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
                    subtitle.setText("アルバムを読み込めません。メニューから再読み込みしてください。");
                });
          }
        });
  }

  private void loadIndexedPhotos() {
    DiagnosticLog.event("screen.loadIndexedPhotos", burstRequest);
    int token = generation;
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
                  if (position == null) grid.selectCell(galleryScroll.getOrDefault(shownGalleryKey, 0));
                  else position.restore(grid, photos, displayed);
                  subtitle.setText(
                      photos.isEmpty() ? "写真がありません。写真の一覧を更新中の場合はしばらくお待ちください。" : countText());
                });
          } catch (Exception e) {
            main.post(
                () -> {
                  if (!isDestroyed() && !albumHome && token == generation)
                    subtitle.setText("一覧を読み込めませんでした");
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
          boolean changed = false;
          try {
            changed = GalleryIndex.rebuild(app, force, () -> false);
          } catch (Exception e) {
            failure = e;
          }
          final boolean failed = failure != null, updated = changed;
          main.post(
              () -> {
                int next = refreshRequests.finish();
                if (isDestroyed()) return;
                if (failed) {
                  toast("写真の一覧を更新できませんでした。保存済みの一覧を表示しています。");
                }
                if (updated) {
                  if (albumHome) loadAlbums();
                  else if (!detail && burstMembers == null) loadIndexedPhotos();
                }
                if (next != IndexRefreshRequests.NONE)
                  refreshIndex(next == IndexRefreshRequests.FORCE);
              });
        });
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
        toast("読み取り許可を保存できませんでした");
      }
    }
  }

  private void toast(String text) {
    Toast.makeText(this, text, Toast.LENGTH_LONG).show();
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
      cell.badges.selected = selecting && tileSelected(members);
      cell.badges.partial = false;
      if (selecting && !cell.badges.selected) for (int member : members)
        if (selectedPhotos.contains(photos.get(member).uri.toString())) cell.badges.partial = true;
      cell.setSelected(cell.badges.selected || cell.badges.partial);
      int inset = cell.badges.selected ? dp(5) : 0;
      image.setPadding(inset, inset, inset, inset);
      if (selecting)
        cell.setContentDescription(
            cell.getContentDescription() + (cell.badges.selected ? "、選択済み" : cell.badges.partial ? "、一部選択" : "、未選択"));
      cell.badges.camera = "";
      cell.badges.cloud = "archive".equals(p.uri.getScheme());
      cell.badges.count = members.size();
      cell.badges.rank = "";
      List<Integer> group = photoBundles.get(members.get(0));
      if (group != null && group.size() > 1) {
        try {
          PhotoRanking saved = rankingFor(group);
          cell.badges.rank = saved.done()
              ? (members.size() > 1 ? "1位" : saved.rankOf(ArchiveStore.key(p)) + "位")
              : "";
        } catch (Exception e) { cell.badges.rank = "読込失敗"; }
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
    LinearLayout top = row();
    top.setPadding(dp(10), dp(8), dp(10), dp(8));


    int within = burstMembers == null ? position : burstMembers.indexOf(position);
    TextView count =
        label(
            (within + 1)
                + " / "
                + (burstMembers == null ? photos.size() : burstMembers.size())
                + (burstMembers == null ? "" : " 連写"),
            13,
            MUTED);
    count.setGravity(Gravity.END);
    top.addView(count, new LinearLayout.LayoutParams(0, -2, 1));
    root.addView(top);
    FrameLayout photoFrame = new FrameLayout(this);
    ZoomImageView image = new ZoomImageView(this);
    image.setPageListener(
        direction -> {
          if (fromRanking) return;
          adjacentPhoto(direction);
        });
    photoFrame.addView(image, new FrameLayout.LayoutParams(-1, -1));
    TextView loadStatus = label("元のファイルを読み込んでいます…", 11, TEXT);
    loadStatus.setPadding(dp(8), dp(5), dp(8), dp(5));
    loadStatus.setBackground(GalleryUi.shape(this, 0xbb151719, 8));
    FrameLayout.LayoutParams statusPosition =
        new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
    statusPosition.setMargins(dp(12), 0, dp(12), dp(8));
    photoFrame.addView(loadStatus, statusPosition);
    root.addView(photoFrame, new LinearLayout.LayoutParams(-1, 0, 1));
    TextView model = label("読み込み中…", 16, TEXT), dimensions = label(photo.name, 11, MUTED);
    Button more =
        button(
            "その他",
            () ->
                new GalleryUi.Sheet(this)
                    .setItems(
                        fromRanking
                            ? new String[] {"写真の情報"}
                            : new String[] {"写真の情報", "前の写真", "次の写真"},
                        (d, which) -> {
                          if (which == 0)
                            new GalleryUi.Sheet(this)
                                .setTitle(photo.name)
                                .setMessage(
                                    model.getText()
                                        + "\n"
                                        + dimensions.getText()
                                        + "\n"
                                        + photo.folder)
                                .setPositiveButton("閉じる", null)
                                .show();
                          else adjacentPhoto(which == 1 ? -1 : 1);
                        })
                    .setNegativeButton("閉じる", null)
                    .show());
    int controlsStart = root.getChildCount();
    dock(button("拡大・戻す", image::toggleZoom));
    if (fromRanking && !ranking.done())
      dock(GalleryUi.neutralPrimary(button("この写真を選ぶ", () -> {
        saveRanking(ranking.choose(rankingMembers.get(ranking.upper()) == position));
      })));
    if (!fromRanking && "content".equals(photo.uri.getScheme()))
      dock(button("タグ", () -> openTags(photo)), button("前の写真", () -> adjacentPhoto(-1)), button("次の写真", () -> adjacentPhoto(1)));
    dock(
        button(
            fromRanking ? "比較に戻る" : "戻る",
            () -> {
              if (fromRanking) showRanking();
              else if (recentPhoto) { recentPhoto = false; albumHome(); }
              else gallery();
            }),
        GalleryUi.primary(button("共有", () -> shareMenu(photo))),
        more);
    GalleryUi.bottomControls(root, controlsStart);
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
                      dimensions.setText("小さい画像・元のファイルを読み込みしています…\n" + photo.name);
                      loadStatus.setText("プレビュー表示中 · 元のファイルを読み込みしています…");
                    });
              } catch (Exception missingPreview) {
                main.post(
                    () -> {
                      if (!isDestroyed() && detail && token == generation)
                        model.setText("元のファイルを読み込みしています…");
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
                      loadStatus.setVisibility(View.GONE);
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
                        loadStatus.setVisibility(View.VISIBLE);
                        loadStatus.setText("元のファイルを読み込みできませんでした · 開き直してください");
                        if (activePreview == null) model.setText("写真を表示できません");
                        dimensions.setText(
                            (activePreview == null ? "" : "小さい画像を表示中\n")
                                + "元のファイルを読み込みできませんでした。通信とログインを確認して開き直してください\n"
                                + photo.name);
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
    } else if (!albumHome) albumHome();
    else super.onBackPressed();
  }

  private void openRanking() {
    DiagnosticLog.event("screen.openRanking", 0);
    try {
      rankingMembers.clear();
      for (int member : burstMembers)
        rankingMembers.put(ArchiveStore.key(photos.get(member)), member);
      List<String> ids = new ArrayList<>(rankingMembers.keySet());
      ranking = rankingStore.load(ids);
      if (ranking.journal.isEmpty()) ranking = new PhotoRanking(ranking.ids, "B:");
      showRanking();
    } catch (Exception e) {
      toast("順位の記録を読み込めませんでした。保存済みの記録は変更していません。");
    }
  }

  private void saveRanking(PhotoRanking next) {
    try {
      if (rankingStore.save(next)) {
        rankCache.clear();
        ranking = next;
        showRanking();
        return;
      }
    } catch (IOException e) {
      // Keep the displayed comparison unchanged when the journal cannot be saved.
    }
    toast("順位を保存できませんでした。空き容量を確認してください。");
  }

  private void showRanking() {
    DiagnosticLog.event("screen.showRanking", 0);
    rememberGallery();
    if (viewing != null) viewing.cancel(true);
    detail = true;
    rankingScreen = true;
    int token = ++generation;
    base();
    LinearLayout top = row();
    top.setPadding(dp(16), dp(8), dp(16), dp(8));
    top.setMinimumHeight(dp(64));

    TextView sortHeading = label(ranking.done() ? "ソート結果" : "ソート", 22, TEXT);
    sortHeading.setAccessibilityHeading(true);
    top.addView(sortHeading, new LinearLayout.LayoutParams(0, -2, 1));
    top.addView(
        label(
            ranking.done()
                ? ranking.ids.size() + "枚"
                : "比較 " + (ranking.decisions() + 1) + "回目",
            12,
            MUTED));
    root.addView(top);
    LinearLayout modes = row();
    modes.setPadding(dp(12), 0, dp(12), dp(8));
    modes.addView(GalleryUi.chip(this, "1位を決める", ranking.bestOnly(),
        () -> changeRankingMode(true)), new LinearLayout.LayoutParams(0, -2, 1));
    modes.addView(GalleryUi.chip(this, "すべての順位を決める", !ranking.bestOnly(),
        () -> changeRankingMode(false)), new LinearLayout.LayoutParams(0, -2, 1));

    TextView hint = label(ranking.done() ? "写真をタップして拡大" : "好きな写真を選んでください", 12, MUTED);
    hint.setPadding(dp(16), 0, dp(16), dp(6));
    root.addView(hint);
    Button undo = button("直前の操作を取り消す", () -> saveRanking(ranking.undo()));
    undo.setEnabled(ranking.decisions() > 0);
    Button chooseA = GalleryUi.neutralPrimary(button("Aを選ぶ", () -> saveRanking(ranking.choose(true))));
    Button chooseB = GalleryUi.neutralPrimary(button("Bを選ぶ", () -> saveRanking(ranking.choose(false))));
    chooseA.setEnabled(false); chooseB.setEnabled(false);
    Button tie = button("同じ順位", () -> saveRanking(ranking.tie()));
    tie.setEnabled(false);
    if (ranking.done()) {
      ListView results = new ListView(this);
      List<String> rows = new ArrayList<>();
      for (int n = 0; n < ranking.order.size(); n++)
        rows.add(
            ranking.rankOf(ranking.order.get(n))
                + "位  ·  "
                + photos.get(rankingMembers.get(ranking.order.get(n))).name);
      results.setAdapter(
          new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, rows) {
            public View getView(int p, View v, ViewGroup parent) {
              LinearLayout item = row();
              item.setPadding(dp(12), dp(6), dp(16), dp(6));
              ImageView thumbnail = new ImageView(MainActivity.this);
              thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
              thumbnail.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
              item.addView(thumbnail, new LinearLayout.LayoutParams(dp(88), dp(72)));
              TextView text = label(rows.get(p), 14, TEXT);
              text.setPadding(dp(16), 0, 0, 0);
              item.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
              PhotoRepository.Photo photo = photos.get(rankingMembers.get(ranking.order.get(p)));
              images.execute(() -> {
                try {
                  Bitmap bitmap = PhotoRepository.bitmap(repository.preview(photo), 200);
                  main.post(() -> {
                    if (!isDestroyed() && token == generation) thumbnail.setImageBitmap(bitmap);
                    else bitmap.recycle();
                  });
                } catch (Exception e) { /* Rank and filename remain available without a preview. */ }
              });
              return item;
            }
          });
      results.setOnItemClickListener(
          (p, v, n, id) -> showRankedPhoto(rankingMembers.get(ranking.order.get(n))));
      root.addView(results, new LinearLayout.LayoutParams(-1, 0, 1));
    } else {

      LinearLayout comparison = new LinearLayout(this);
      root.addView(comparison, new LinearLayout.LayoutParams(-1, 0, 1));
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
            hint.setText(horizontal ? "Aは左の写真 · Bは右の写真" : "Aは上の写真 · Bは下の写真");
            for (ImageView image : candidates)
              image.setLayoutParams(
                  new LinearLayout.LayoutParams(horizontal ? 0 : -1, horizontal ? -1 : 0, 1));
          };
      comparison.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> arrange.run());
      rankingCandidate(ranking.upper(), true, token, comparison, candidates, ratios, arrange);
      rankingCandidate(ranking.lower(), false, token, comparison, candidates, ratios, arrange);
    }
    int controlsStart = root.getChildCount();
    if (!ranking.done()) {
      dock(chooseA, chooseB);
      dock(button("Aを拡大", () -> showRankedPhoto(rankingMembers.get(ranking.upper()))),
          button("Bを拡大", () -> showRankedPhoto(rankingMembers.get(ranking.lower()))));
    }
    root.addView(modes);
    Button back = button(rankingFromSelection ? "選択へ戻る" : "連写一覧", this::leaveRanking);
    if (ranking.done()) dock(back, undo, button("その他", this::rankingMenu));
    else if (ranking.bestOnly()) dock(back, undo, button("その他", this::rankingMenu));
    else dock(back, undo, tie, button("その他", this::rankingMenu));
    GalleryUi.bottomControls(root, controlsStart);
  }

  private void changeRankingMode(boolean best) {
    if (ranking.bestOnly() == best) return;
    new GalleryUi.Sheet(this).setTitle("ソート方式を変更しますか？")
        .setMessage("この連写の結果と途中の判断を置き換え、最初から比較します。")
        .setPositiveButton("変更", (d, w) -> saveRanking(new PhotoRanking(ranking.ids, best ? "B:" : "A:")))
        .setNegativeButton("戻る", null).show();
  }

  private void rankingMenu() {
    new GalleryUi.Sheet(this)
        .setItems(
            new String[] {"保留して一覧へ", "再ソート"},
            (d, choice) -> {
              if (choice == 0) leaveRanking();
              else
                new GalleryUi.Sheet(this)
                    .setTitle("この連写を再ソートしますか？")
                    .setMessage("この連写の選択履歴を消して、最初から比較します。写真は変更しません。")
                    .setPositiveButton(
                        "再ソート", (dialog, which) -> saveRanking(new PhotoRanking(ranking.ids, ranking.bestOnly() ? "B:" : "A:")))
                    .setNegativeButton("戻る", null)
                    .show();
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
    ImageView image = new ImageView(this);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    image.setContentDescription(photo.name + "。タップで選択、長押しで拡大");
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
                  ratios[upper ? 0 : 1] = (double) bitmap.getWidth() / bitmap.getHeight();
                  if (ratios[0] > 0 && ratios[1] > 0) {
                    arrange.run();
                    for (ImageView candidate : candidates) candidate.setEnabled(true);
                  }
                });
          } catch (Exception e) {
            main.post(
                () -> {
                  if (!isDestroyed() && token == generation)
                    toast("写真を読み込めませんでした。一覧へ戻って開き直してください。");
                });
          }
        });
  }

  private void shareMenu(PhotoRepository.Photo photo) {
    shareMenu(List.of(photo));
  }

  private void shareMenu(List<PhotoRepository.Photo> selection) {
    List<PhotoRepository.Photo> frozen = List.copyOf(selection);
    List<ShareTargets.Target> savedTargets = ShareTargets.load(this);
    boolean[] raw = {false};
    LinearLayout sheet = vertical();
    sheet.setPadding(dp(20), dp(4), dp(20), dp(16));
    TextView description = label("写真を元の品質で渡します", 13, MUTED);
    sheet.addView(description);
    LinearLayout formats = row();
    formats.setPadding(0, dp(12), 0, dp(12));
    sheet.addView(formats);
    AlertDialog[] dialog = new AlertDialog[1];
    Runnable renderFormats = new Runnable() {
      public void run() {
        formats.removeAllViews();
        formats.addView(GalleryUi.chip(MainActivity.this, "JPEGで共有", !raw[0], () -> {
          raw[0] = false; run();
        }), new LinearLayout.LayoutParams(0, -2, 1));
        formats.addView(GalleryUi.chip(MainActivity.this, "元の形式で共有", raw[0], () -> {
          raw[0] = true; run();
        }), new LinearLayout.LayoutParams(0, -2, 1));
        description.setText(raw[0] ? "撮影した形式のまま · RAW / JPEG" : "RAWの場合は、ファイル内のJPEG画像を使います");
      }
    };
    renderFormats.run();
    sheet.addView(GalleryUi.primary(button("共有先を選ぶ", () -> {
      dialog[0].dismiss(); export(frozen, raw[0], null);
    })), new LinearLayout.LayoutParams(-1, dp(52)));
    if (!savedTargets.isEmpty()) {
      TextView title = label("登録した共有先", 13, MUTED);
      title.setPadding(0, dp(20), 0, dp(4));
      sheet.addView(title);
      for (ShareTargets.Target target : savedTargets) {
        Button item = button(target.label + " · " + (target.raw ? "元のファイル" : "JPEG"), () -> {
          dialog[0].dismiss(); export(frozen, target.raw, target);
        });
        item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        sheet.addView(item);
      }
    }
    ScrollView scroll = new ScrollView(this);
    scroll.addView(sheet);
    dialog[0] = new GalleryUi.Sheet(this).setTitle(frozen.size() + "枚を共有")
        .setView(scroll).setNegativeButton("閉じる", null).show();
  }

  private void export(
      List<PhotoRepository.Photo> selection, boolean raw, ShareTargets.Target target) {
    if (shareProgress != null && shareProgress.isShowing()) return;
    final int attempt = ++shareAttempt;
    shareProgress = new GalleryUi.Sheet(this)
        .setTitle(selection.size() + "枚を準備中")
        .setMessage((raw ? "元のファイル" : "JPEG画像") + "を準備しています。選択は保持されます。")
        .setCancelable(false)
        .setNegativeButton("中止", (d, w) -> {
          shareAttempt++;
          if (sharing != null) sharing.cancel(true);
          toast("共有の準備を中止しました");
        }).show();
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
                      toast("この共有先は選んだ画像形式に対応していません。共有先の形式を変更してください。");
                      return;
                    }
                    send.setComponent(component);
                  }
                  try {
                    startActivity(target == null ? Intent.createChooser(send, "写真を共有") : send);
                  } catch (ActivityNotFoundException e) {
                    toast("共有先が見つかりません。共有先の設定を変更してください。");
                  }
                });
          } catch (Exception e) {
            main.post(() -> {
              if (isDestroyed() || attempt != shareAttempt) return;
              shareProgress.dismiss();
              new GalleryUi.Sheet(this).setTitle("共有を準備できませんでした")
                  .setMessage(selection.size() + "枚の選択は保持されています。空き容量・通信・読み取り許可を確認してください。")
                  .setPositiveButton("再試行", (d, w) -> export(selection, raw, target))
                  .setNegativeButton("選択に戻る", null).show();
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
        .setNegativeButton("戻る", (d, w) -> editTargets())
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
                  toast("登録済みです");
                  return;
                }
              targets.add(new ShareTargets.Target(component, names[i], false));
              ShareTargets.save(this, targets);
              editTargets();
            })
        .setNegativeButton("戻る", (d, w) -> editTargets())
        .show();
  }

  protected void onDestroy() {
    generation++;
    shareAttempt++;
    if (shareProgress != null) shareProgress.dismiss();
    getContentResolver().unregisterContentObserver(mediaObserver);
    main.removeCallbacks(mediaChanged);
    scans.shutdownNow();
    images.shutdownNow();
    actions.shutdownNow();
    super.onDestroy();
  }
}
