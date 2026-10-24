package jp.nano.rawgallery;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.*;
import android.util.LruCache;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
  private static final int BG = GalleryUi.BG,
      PANEL = GalleryUi.PANEL,
      TEXT = GalleryUi.TEXT,
      MUTED = GalleryUi.MUTED,
      ACCENT = GalleryUi.ACCENT;
  private final ExecutorService scans = Executors.newSingleThreadExecutor(),
      images = Executors.newFixedThreadPool(2),
      actions = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final LruCache<String, Bitmap> thumbs =
      new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        protected int sizeOf(String k, Bitmap b) {
          return b.getAllocationByteCount();
        }
      };
  private PhotoRepository repository;
  private List<List<Integer>> bundles = new ArrayList<>();
  private List<Integer> burstMembers;
  private List<List<Integer>> displayed = new ArrayList<>();
  private List<PhotoRepository.Photo> photos = new ArrayList<>();
  private LinearLayout root, body;
  private TextView subtitle;
  private GridView grid, albumGridView;
  private int albumScroll;
  private String shownGalleryKey;
  private final Map<String, Integer> galleryScroll = new HashMap<>();

  private void rememberGallery() {
    if (!albumHome && !detail && grid != null && shownGalleryKey != null)
      galleryScroll.put(shownGalleryKey, grid.getFirstVisiblePosition());
  }

  private boolean selecting, bestOnly = true, rankingFromSelection;
  private final Set<Integer> selectedTiles = new LinkedHashSet<>();
  private List<Integer> selectionBurst;
  private TextView selectionCount;
  private Button selectionShare, selectionAll;
  private GalleryAdapter adapter;
  private String filter = "すべて";
  private String albumFolder;
  private boolean albumHome = true, indexing, indexAgain, resumed;
  private final long startedAt = SystemClock.elapsedRealtime();
  private boolean reportedAlbums, reportedCover;
  private final Runnable mediaChanged = () -> refresh();
  private final android.database.ContentObserver mediaObserver =
      new android.database.ContentObserver(main) {
        public void onChange(boolean selfChange) {
          main.removeCallbacks(mediaChanged);
          main.postDelayed(mediaChanged, 1500);
        }
      };
  private int generation = 0;
  private Future<?> viewing;
  private boolean detail = false;
  private boolean rankingScreen = false;
  private PhotoRanking ranking;
  private String rankingKey;
  private final Map<String, Integer> rankingMembers = new HashMap<>();
  private int index = 0;
  private PhotoRepository.Preview activePreview;
  private List<ShareTargets.Target> targets;

  public void onCreate(Bundle state) {
    super.onCreate(state);
    repository = new PhotoRepository(this);
    targets = ShareTargets.load(this);
    albumHome();
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
    rememberGallery();
    if (albumHome && albumGridView != null) albumScroll = albumGridView.getFirstVisiblePosition();
    albumHome = false;
    if (viewing != null) viewing.cancel(true);
    detail = false;
    rankingScreen = false;
    generation++;
    activePreview = null;
    base();
    LinearLayout header = row();
    header.setPadding(dp(16), dp(8), dp(16), dp(8));
    header.setMinimumHeight(dp(48));
    header.addView(
        label(burstMembers == null ? "写真" : "連写", 22, TEXT),
        new LinearLayout.LayoutParams(0, -2, 1));
    subtitle = label("読み込み中…", 12, MUTED);
    header.addView(subtitle);
    root.addView(header);
    body = vertical();
    root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
    grid = new GridView(this);
    grid.setNumColumns(3);
    grid.setHorizontalSpacing(dp(3));
    grid.setVerticalSpacing(dp(3));
    grid.setPadding(dp(3), 0, dp(3), 0);
    grid.setClipToPadding(false);
    displayed = new ArrayList<>();
    if (burstMembers == null) displayed.addAll(bundles);
    else for (int member : burstMembers) displayed.add(List.of(member));
    adapter = new GalleryAdapter();
    grid.setAdapter(adapter);
    shownGalleryKey = albumFolder + "|" + filter + "|" + String.valueOf(burstMembers);
    grid.setSelection(galleryScroll.getOrDefault(shownGalleryKey, 0));
    grid.setOnItemClickListener(
        (p, v, pos, id) -> {
          if (selecting) {
            root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            toggleSelection(pos);
            return;
          }
          GalleryUi.feedback(v);
          List<Integer> members = displayed.get(pos);
          if (members.size() > 1) {
            burstMembers = new ArrayList<>(members);
            gallery();
          } else showPhoto(members.get(0));
        });
    // The grid is rebuilt by this listener, so emit before detaching it.
    grid.setHapticFeedbackEnabled(false);
    grid.setOnItemLongClickListener(
        (parent, view, pos, id) -> {
          root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
          if (!selecting) {
            selecting = true;
            bestOnly = true;
            selectedTiles.clear();
          }
          toggleSelection(pos);
          gallery();
          return true;
        });
    body.addView(grid, new LinearLayout.LayoutParams(-1, -1));
    if (selecting) selectionControls();
    else
      dock(
          button(
              burstMembers == null ? "アルバムへ" : "写真一覧",
              () -> {
                if (burstMembers == null) albumHome();
                else {
                  burstMembers = null;
                  gallery();
                }
              }),
          burstMembers == null
              ? button("絞り込み", this::chooseFilter)
              : GalleryUi.primary(button("順位を付ける", this::openRanking)),
          button("その他", this::galleryMenu));
    if (selecting) updateSelection();
    else if (!photos.isEmpty()) updateCount();
  }

  private String rankingKey(List<String> ids) throws IOException {
    List<String> sorted = new ArrayList<>(ids);
    Collections.sort(sorted);
    return "v1-" + PhotoRepository.digest(String.join("|", sorted));
  }

  private PhotoRanking rankingFor(List<Integer> members) throws IOException {
    List<String> ids = new ArrayList<>();
    for (int i : members) ids.add(ArchiveStore.key(photos.get(i)));
    Collections.sort(ids);
    return new PhotoRanking(
        ids, getSharedPreferences("rankings", 0).getString(rankingKey(ids), ""));
  }

  private static final class SelectionResult {
    final List<PhotoRepository.Photo> photos = new ArrayList<>();
    final List<List<Integer>> unresolved = new ArrayList<>();
  }

  private SelectionResult resolveSelection() {
    Set<Integer> requested = new LinkedHashSet<>();
    for (int tile : selectedTiles)
      if (tile < displayed.size()) requested.addAll(displayed.get(tile));
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

  private void toggleSelection(int pos) {
    if (!selectedTiles.remove(pos)) selectedTiles.add(pos);
    adapter.notifyDataSetChanged();
    updateSelection();
  }

  private void selectionControls() {
    CheckBox check = new CheckBox(this);
    check.setText("ベストショットだけを操作する");
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
              if (selectedTiles.size() == displayed.size()) selectedTiles.clear();
              else for (int i = 0; i < displayed.size(); i++) selectedTiles.add(i);
              adapter.notifyDataSetChanged();
              updateSelection();
            });
    dock(button("選択を終了", this::endSelection), selectionShare, selectionAll);
    updateSelection();
  }

  private void updateSelection() {
    if (!selecting || selectionCount == null) return;
    SelectionResult result = resolveSelection();
    subtitle.setText(selectedTiles.size() + "項目選択");
    selectionCount.setText(
        "対象 "
            + result.photos.size()
            + "枚"
            + (bestOnly ? " · 各連写の１位（同率を含む）" : "")
            + (result.unresolved.isEmpty() ? "" : " · 順位未確定 " + result.unresolved.size() + "組"));
    selectionShare.setEnabled(!result.photos.isEmpty() || !result.unresolved.isEmpty());
    String allLabel =
        !selectedTiles.isEmpty() && selectedTiles.size() == displayed.size()
            ? "すべての選択を解除"
            : "すべて選択";
    selectionAll.setContentDescription(allLabel);
    selectionAll.setTooltipText(allLabel);
  }

  private void endSelection() {
    selecting = false;
    selectedTiles.clear();
    selectionCount = null;
    selectionShare = null;
    gallery();
  }

  private void shareSelection() {
    SelectionResult result = resolveSelection();
    if (!result.unresolved.isEmpty()) {
      GalleryUi.Sheet sheet = new GalleryUi.Sheet(this);
      sheet
          .setTitle("順位が未確定の連写があります")
          .setMessage(result.unresolved.size() + "組の順位が未確定です。順位を付けてから選択画面へ戻れます。")
          .setPositiveButton(
              "順位を付ける",
              (d, w) -> {
                selectionBurst = burstMembers == null ? null : new ArrayList<>(burstMembers);
                rankingFromSelection = true;
                burstMembers = new ArrayList<>(result.unresolved.get(0));
                openRanking();
              })
          .setNegativeButton("戻る", null);
      if (!result.photos.isEmpty())
        sheet.setNeutralButton("確定分だけ共有", (d, w) -> shareMenu(result.photos));
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
              photos = new ArrayList<>();
              bundles = new ArrayList<>();
              burstMembers = null;
              gallery();
              refresh();
            })
        .setNegativeButton("閉じる", null)
        .show();
  }

  private void galleryMenu() {
    new GalleryUi.Sheet(this)
        .setTitle("設定とその他の操作")
        .setItems(
            new String[] {"クラウドと同期", "共有先の設定", "連写のまとめ方", "写真へのアクセス", "再読み込み"},
            (d, which) -> {
              switch (which) {
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
        ? photos.size() + "枚 · " + bundles.size() + "枠"
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
    top.addView(label("Loupe", 22, TEXT), new LinearLayout.LayoutParams(0, -2, 1));
    subtitle = label("読み込み中…", 12, MUTED);
    top.addView(subtitle);
    root.addView(top);
    body = vertical();
    root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
    dock(
        button(
            "すべての写真",
            () -> {
              albumFolder = null;
              filter = "すべて";
              photos = new ArrayList<>();
              bundles = new ArrayList<>();
              gallery();
              loadIndexedPhotos();
            }),
        button("その他", this::galleryMenu));
    loadAlbums();
  }

  private void loadAlbums() {
    int token = generation;
    scans.execute(
        () -> {
          try (GalleryIndex catalog = new GalleryIndex(this)) {
            List<GalleryIndex.Album> albums = catalog.albums();
            main.post(
                () -> {
                  if (isDestroyed() || !albumHome || token != generation) return;
                  subtitle.setText(albums.isEmpty() ? "初回の索引を作成しています…" : albums.size() + " アルバム");
                  if (albumGridView != null) albumScroll = albumGridView.getFirstVisiblePosition();
                  body.removeAllViews();
                  GridView albumGrid = new GridView(this);
                  albumGridView = albumGrid;
                  albumGrid.setNumColumns(2);
                  albumGrid.setHorizontalSpacing(dp(4));
                  albumGrid.setVerticalSpacing(dp(4));
                  albumGrid.setAdapter(
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
                            ImageView cover = new ImageView(MainActivity.this);
                            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                            cell.addView(cover, new LinearLayout.LayoutParams(-1, dp(150)));
                            TextView caption = label("", 13, TEXT);
                            caption.setPadding(dp(10), dp(8), dp(10), dp(10));
                            cell.addView(caption);
                          }
                          GalleryIndex.Album album = albums.get(p);
                          ImageView cover = (ImageView) cell.getChildAt(0);
                          TextView caption = (TextView) cell.getChildAt(1);
                          caption.setText(album.name() + " · " + album.count + "枚");
                          cell.setContentDescription(
                              "アルバム、" + album.folder + "、" + album.count + "枚");
                          String key =
                              album.cover.uri + ":" + album.cover.modified + ":" + album.cover.size;
                          cover.setTag(key);
                          cover.setImageDrawable(null);
                          Bitmap cached = thumbs.get(key);
                          if (cached != null) cover.setImageBitmap(cached);
                          else
                            images.execute(
                                () -> {
                                  try {
                                    Bitmap bitmap =
                                        PhotoRepository.bitmap(
                                            repository.preview(album.cover), 600);
                                    thumbs.put(key, bitmap);
                                    main.post(
                                        () -> {
                                          if (!isDestroyed()
                                              && token == generation
                                              && key.equals(cover.getTag())) {
                                            cover.setImageBitmap(bitmap);
                                            if (!reportedCover) {
                                              reportedCover = true;
                                              cover.postOnAnimation(
                                                  () ->
                                                      android.util.Log.i(
                                                          "YohakuStartup",
                                                          "album_cover_ms="
                                                              + (SystemClock.elapsedRealtime()
                                                                  - startedAt)));
                                            }
                                          }
                                        });
                                  } catch (Exception ignored) {
                                  }
                                });
                          return cell;
                        }
                      });
                  albumGrid.setSelection(albumScroll);
                  albumGrid.setOnItemClickListener(
                      (p, v, pos, id) -> {
                        albumFolder = albums.get(pos).folder;
                        filter = "すべて";
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
                      || rankingScreen
                      || token != generation) return;
                  photos = snapshot.photos;
                  bundles = snapshot.bundles;
                  burstMembers = null;
                  displayed = new ArrayList<>(bundles);
                  adapter.notifyDataSetChanged();
                  grid.setSelection(galleryScroll.getOrDefault(shownGalleryKey, 0));
                  subtitle.setText(
                      photos.isEmpty() ? "写真がありません。索引を更新中の場合はしばらくお待ちください。" : countText());
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
    if (!detail) {
      if (albumHome) loadAlbums();
      else if (burstMembers == null) loadIndexedPhotos();
    }
    refreshIndex(false);
  }

  private void refreshIndex(boolean force) {
    if (indexing) {
      indexAgain |= force;
      return;
    }
    indexing = true;
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
                indexing = false;
                if (isDestroyed()) return;
                if (failed) {
                  toast("索引を更新できませんでした。保存済みの一覧を表示しています。");
                  return;
                }
                if (updated) {
                  if (albumHome) loadAlbums();
                  else if (!detail && burstMembers == null) loadIndexedPhotos();
                }
                if (indexAgain) {
                  indexAgain = false;
                  refreshIndex(true);
                }
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
      PhotoRepository.Photo p = photos.get(members.get(0));
      String key = p.uri + ":" + p.modified + ":" + p.size;
      image.setTag(key);
      cell.setContentDescription(
          p.name
              + "、"
              + p.cameraGroup()
              + ("archive".equals(p.uri.getScheme()) ? "、クラウド" : "、端末")
              + (members.size() > 1 ? "、連写" + members.size() + "枚" : ""));
      image.setImageDrawable(null);
      cell.badges.selectionMode = selecting;
      cell.badges.selected = selecting && selectedTiles.contains(position);
      cell.setSelected(cell.badges.selected);
      if (selecting)
        cell.setContentDescription(
            cell.getContentDescription() + (cell.badges.selected ? "、選択済み" : "、未選択"));
      cell.badges.camera = p.cameraGroup();
      cell.badges.cloud = "archive".equals(p.uri.getScheme());
      cell.badges.count = members.size();
      cell.badges.failed = false;
      cell.badges.invalidate();
      Bitmap cached = thumbs.get(key);
      if (cached != null) {
        image.setImageBitmap(cached);
        return cell;
      }
      ImageView target = image;
      ThumbnailCell targetCell = cell;
      images.execute(
          () -> {
            if (!key.equals(target.getTag()) || isDestroyed()) return;
            try {
              PhotoRepository.Preview preview = repository.preview(p);
              Bitmap bitmap = PhotoRepository.bitmap(preview, 400);
              thumbs.put(key, bitmap);
              main.post(
                  () -> {
                    if (!isDestroyed() && key.equals(target.getTag()))
                      target.setImageBitmap(bitmap);
                  });
            } catch (Exception e) {
              main.post(
                  () -> {
                    if (!isDestroyed() && key.equals(target.getTag())) {
                      targetCell.badges.failed = true;
                      targetCell.badges.invalidate();
                      targetCell.setContentDescription(
                          targetCell.getContentDescription() + "、プレビューなし");
                    }
                  });
            }
          });
      return cell;
    }
  }

  private void showPhoto(int position) {
    showPhoto(position, false);
  }

  private boolean returnToRanking;

  private void showPhoto(int position, boolean fromRanking) {
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
    TextView loadStatus = label("原本を読み込んでいます…", 11, TEXT);
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
    dock(
        button(
            fromRanking ? "比較に戻る" : "一覧",
            () -> {
              if (fromRanking) showRanking();
              else gallery();
            }),
        GalleryUi.primary(button("共有", () -> shareMenu(photo))),
        more);
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
                      dimensions.setText("小サイズのプレビュー・原本を取得しています…\n" + photo.name);
                      loadStatus.setText("プレビュー表示中 · 原本を取得しています…");
                    });
              } catch (Exception missingPreview) {
                main.post(
                    () -> {
                      if (!isDestroyed() && detail && token == generation)
                        model.setText("原本を取得しています…");
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
                              + (photo.isJpeg() ? "  ·  原本" : "  ·  原本内のJPEG")
                              + "\n"
                              + photo.name);
                    });
              } catch (Exception unavailable) {
                main.post(
                    () -> {
                      if (!isDestroyed() && detail && token == generation) {
                        loadStatus.setVisibility(View.VISIBLE);
                        loadStatus.setText("原本を取得できませんでした · 開き直してください");
                        if (activePreview == null) model.setText("写真を表示できません");
                        dimensions.setText(
                            (activePreview == null ? "" : "小サイズのプレビューを表示中\n")
                                + "原本を取得できませんでした。通信とログインを確認して開き直してください\n"
                                + photo.name);
                      }
                    });
              }
            });
  }

  public void onBackPressed() {
    if (rankingScreen) leaveRanking();
    else if (selecting && !detail) endSelection();
    else if (detail) {
      if (returnToRanking) showRanking();
      else gallery();
    } else if (burstMembers != null) {
      burstMembers = null;
      gallery();
    } else if (!albumHome) albumHome();
    else super.onBackPressed();
  }

  private void openRanking() {
    try {
      rankingMembers.clear();
      for (int member : burstMembers)
        rankingMembers.put(ArchiveStore.key(photos.get(member)), member);
      List<String> ids = new ArrayList<>(rankingMembers.keySet());
      Collections.sort(ids);
      rankingKey = rankingKey(ids);
      ranking =
          new PhotoRanking(ids, getSharedPreferences("rankings", 0).getString(rankingKey, ""));
      showRanking();
    } catch (Exception e) {
      toast("順位の記録を読み込めませんでした。保存済みの記録は変更していません。");
    }
  }

  private void saveRanking(PhotoRanking next) {
    if (!getSharedPreferences("rankings", 0).edit().putString(rankingKey, next.journal).commit()) {
      toast("順位を保存できませんでした。空き容量を確認してください。");
      return;
    }
    ranking = next;
    showRanking();
  }

  private void showRanking() {
    rememberGallery();
    if (viewing != null) viewing.cancel(true);
    detail = true;
    rankingScreen = true;
    int token = ++generation;
    base();
    LinearLayout top = row();
    top.setPadding(dp(16), dp(8), dp(16), dp(8));
    top.setMinimumHeight(dp(48));
    top.addView(
        label(ranking.done() ? "順位" : "連写 " + ranking.ids.size() + "枚", 22, TEXT),
        new LinearLayout.LayoutParams(0, -2, 1));
    top.addView(
        label(
            ranking.done()
                ? ranking.ids.size() + "枚"
                : "比較 " + (ranking.journal.length() + 1) + "回目",
            12,
            MUTED));
    root.addView(top);
    TextView hint = label(ranking.done() ? "写真をタップして原本を確認" : "好きな写真をタップ · 長押しで原本", 12, MUTED);
    hint.setPadding(dp(16), 0, dp(16), dp(6));
    root.addView(hint);
    Button undo = button("一つ戻す", () -> saveRanking(ranking.undo()));
    undo.setEnabled(!ranking.journal.isEmpty());
    Button tie = button("同順位", () -> saveRanking(ranking.tie()));
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
              TextView text = (TextView) super.getView(p, v, parent);
              text.setTextColor(TEXT);
              return text;
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
            tie.setEnabled(true);
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
    }
    Button back = button(rankingFromSelection ? "選択へ戻る" : "連写一覧", this::leaveRanking);
    if (ranking.done()) dock(back, undo, button("その他", this::rankingMenu));
    else dock(back, undo, tie, button("その他", this::rankingMenu));
  }

  private void rankingMenu() {
    new GalleryUi.Sheet(this)
        .setItems(
            new String[] {"保留して一覧へ", "最初からやり直す"},
            (d, choice) -> {
              if (choice == 0) leaveRanking();
              else
                new GalleryUi.Sheet(this)
                    .setTitle("この連写の順位を付け直しますか？")
                    .setMessage("この連写の選択履歴を消して、最初から比較します。写真は変更しません。")
                    .setPositiveButton(
                        "やり直す", (dialog, which) -> saveRanking(new PhotoRanking(ranking.ids, "")))
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
    image.setContentDescription(photo.name + "。タップで選択、長押しで原本を確認");
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
    targets = ShareTargets.load(this);
    List<String> labels = new ArrayList<>();
    for (ShareTargets.Target t : targets)
      labels.add(t.label + "  /  " + (t.raw ? "原本" : "原本のJPEG"));
    labels.add("ほかのアプリへ原本のJPEGを共有");
    labels.add("ほかのアプリへ原本を共有");
    new GalleryUi.Sheet(this)
        .setTitle(frozen.size() + "枚を共有")
        .setItems(
            labels.toArray(new String[0]),
            (d, i) -> {
              if (i < targets.size()) export(frozen, targets.get(i).raw, targets.get(i));
              else export(frozen, i == targets.size() + 1, null);
            })
        .setNegativeButton("閉じる", null)
        .show();
  }

  private void export(
      List<PhotoRepository.Photo> selection, boolean raw, ShareTargets.Target target) {
    toast("共有する画像を準備しています");
    actions.execute(
        () -> {
          List<File> prepared = new ArrayList<>();
          try {
            ArrayList<Uri> uris = new ArrayList<>();
            Set<String> types = new HashSet<>();
            File dir = new File(getCacheDir(), "shares");
            dir.mkdirs();
            File[] old = dir.listFiles();
            long total = 0;
            if (old != null)
              for (File f : old) {
                if (f.lastModified() < System.currentTimeMillis() - 24L * 60 * 60 * 1000)
                  f.delete();
                else total += f.length();
              }
            if (total > 512L * 1024 * 1024)
              throw new IOException("共有用キャッシュの上限です。しばらく待って再試行してください。");
            for (PhotoRepository.Photo photo : selection) {
              if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
              String ext =
                  raw
                      ? (photo.isJpeg()
                          ? "jpg"
                          : (photo.name.toLowerCase(Locale.ROOT).endsWith(".orf") ? "orf" : "dng"))
                      : "jpg";
              File file = new File(dir, UUID.randomUUID() + "." + ext);
              prepared.add(file);
              PhotoRepository.Photo original = OriginalCache.obtain(this, photo);
              if (raw || photo.isJpeg()) {
                try (InputStream in = PhotoRepository.original(this, original);
                    OutputStream out = new FileOutputStream(file)) {
                  byte[] buffer = new byte[65536];
                  int n;
                  while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                }
              } else {
                PhotoRepository.Preview p = repository.fullPreview(original);
                try (InputStream in = new FileInputStream(p.file);
                    OutputStream out = new FileOutputStream(file)) {
                  byte[] buffer = new byte[65536];
                  int n;
                  while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                }
                ExifInterface exif = new ExifInterface(file);
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(p.orientation));
                exif.saveAttributes();
              }
              Uri uri = Uri.parse("content://jp.nano.rawgallery.share/" + file.getName());
              String type =
                  raw && !photo.isJpeg()
                      ? (ext.equals("orf") ? "image/x-olympus-orf" : "image/x-adobe-dng")
                      : "image/jpeg";
              uris.add(uri);
              types.add(type);
              total += file.length();
              if (total > 512L * 1024 * 1024) throw new IOException("共有用キャッシュの上限です");
            }
            String type = types.size() == 1 ? types.iterator().next() : "image/*";
            String action = uris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE;
            main.post(
                () -> {
                  if (isDestroyed()) return;
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
            for (File file : prepared) file.delete();
            main.post(() -> toast("共有の準備に失敗しました。空き容量と読み取り許可を確認してください。"));
          }
        });
  }

  private void editTargets() {
    targets = ShareTargets.load(this);
    List<String> options = new ArrayList<>();
    for (int i = 0; i < targets.size(); i++) {
      ShareTargets.Target t = targets.get(i);
      options.add((i + 1) + ". " + t.label + "  /  " + (t.raw ? "原本" : "原本のJPEG"));
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
            new String[] {"原本のJPEGを渡す", "原本を渡す", "一つ上へ移動", "一つ下へ移動", "登録を外す"},
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
    for (String mime : new String[] {"image/jpeg", "image/x-adobe-dng", "image/x-olympus-orf"})
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
    getContentResolver().unregisterContentObserver(mediaObserver);
    main.removeCallbacks(mediaChanged);
    scans.shutdownNow();
    images.shutdownNow();
    actions.shutdownNow();
    super.onDestroy();
  }
}
