package jp.nano.rawgallery;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.concurrent.Future;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Human judgments and separately stored, correctable automatic assignments. */
public final class TagActivity extends DiagnosticActivity {
  private final ExecutorService worker = new DiagnosticExecutor("TagActivity.worker", 1);
  private final Handler main = new Handler(Looper.getMainLooper());
  private final List<PhotoRepository.Photo> photos = new ArrayList<>();
  private final List<String> photoIds = new ArrayList<>();
  private final ExecutorService analysis = new DiagnosticExecutor("TagActivity.analysis", 1);
  private final Map<Integer, RelatedPhotos.Vector> features = new HashMap<>();
  private Future<?> analyzing;
  private volatile int analysisGeneration;
  private boolean related, analysisFinished;
  private int analyzed, analysisFailures, automaticCount;
  private volatile boolean analysisPaused;
  private boolean analysisActive;
  private final Set<Integer> processed = new HashSet<>();
  private TextView analysisStatus, jobTitle;
  private Button resultButton;
  private String automaticError = "";
  private Map<String, AutomaticTags.Decision> automatic = new HashMap<>();
  private boolean autoRunning, autoPending, autoAgain;
  private long autoEpoch;
  private TagJudgments judgments = new TagJudgments();
  private TagStore.Tag tag;
  private LinearLayout root;
  private int generation;
  private boolean busy, reviewing;
  private int browseMode;
  private boolean selecting;
  private final Set<Integer> selectedPhotos = new LinkedHashSet<>();
  private android.widget.GridView photoGrid;
  private android.widget.BaseAdapter photoAdapter;
  private Button selectionButton, batchButton;
  private enum Filter { ATTACHED, ALL, AUTOMATIC, REMOVED }
  private Filter browseFilter = Filter.ATTACHED;
  private final List<Integer> browsePhotos = new ArrayList<>();

  public void onCreate(Bundle state) {
    super.onCreate(state);
    showTags();
  }

  private TextView text(String value, int size) {
    TextView view = new TextView(this);
    view.setText(value); view.setTextSize(size); view.setTextColor(GalleryUi.TEXT);
    view.setPadding(GalleryUi.dp(this, 16), GalleryUi.dp(this, 6), GalleryUi.dp(this, 16), GalleryUi.dp(this, 6));
    return view;
  }

  private Button button(String title, Runnable action) { return GalleryUi.button(this, title, action); }

  private int screen(String title) {
    generation++;
    analysisStatus = null; jobTitle = null; resultButton = null;
    root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(GalleryUi.BG);
    root.setOnApplyWindowInsetsListener((view, insets) -> {
      Insets safe = insets.getInsets(WindowInsets.Type.systemBars());
      view.setPadding(safe.left, safe.top, safe.right, safe.bottom); return insets;
    });
    setContentView(root); root.addView(text(title, 22));
    return generation;
  }

  private boolean current(int token) { return !isDestroyed() && token == generation; }
  private void failure(Exception error) {
    Toast.makeText(this, "保存・読み込みに失敗しました。もう一度お試しください。", Toast.LENGTH_LONG).show();
  }

  private void showTags() {
    if (busy) return;
    stopAnalysis();
    reviewing = false;
    int token = screen("タグ");
    root.addView(text("現在のアルバム・絞り込み内の端末写真が対象です", 13));
    LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
    ScrollView scroll = new ScrollView(this); scroll.addView(list);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    root.addView(button("新しいタグ", this::createTag));
    root.addView(button("写真に戻る", this::finish));
    worker.execute(() -> {
      try (TagStore store = new TagStore(this)) {
        List<TagStore.Tag> tags = store.tags();
        main.post(() -> {
          if (!current(token)) return;
          if (tags.isEmpty()) list.addView(text("写真に付けるタグを作成してください", 16));
          for (TagStore.Tag item : tags) list.addView(button(item.name, () -> openTag(item)));
        });
      } catch (Exception error) { main.post(() -> { if (current(token)) failure(error); }); }
    });
  }

  private void createTag() {
    EditText input = new EditText(this);
    input.setSingleLine(true); input.setHint("タグ名"); input.setTextColor(GalleryUi.TEXT);
    AlertDialog dialog = new GalleryUi.Sheet(this).setTitle("新しいタグ").setView(input)
        .setPositiveButton("作成", null).setNegativeButton("戻る", null).create();
    dialog.setOnShowListener(ignored -> { GalleryUi.styleSheet(dialog); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
      String name = input.getText().toString().trim();
      if (name.isEmpty() || name.length() > 80) { input.setError("1〜80文字で入力してください"); return; }
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
      int token = generation;
      worker.execute(() -> {
        try (TagStore store = new TagStore(this)) {
          TagStore.Tag created = store.create(name);
          main.post(() -> { if (current(token)) { dialog.dismiss(); openTag(created); } });
        } catch (Exception error) {
          main.post(() -> { if (current(token)) { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); failure(error); } });
        }
      });
    }); });
    dialog.show();
  }

  private void openTag(TagStore.Tag selected) {
    stopAnalysis();
    tag = selected; reviewing = false;
    int token = screen(tag.name);
    root.addView(text("写真と判断を読み込み中…", 15));
    worker.execute(() -> {
      try (TagStore store = new TagStore(this); GalleryIndex index = new GalleryIndex(this)) {
        GalleryIndex.Snapshot snapshot = index.load(getIntent().getStringExtra("folder"),
            getIntent().getStringExtra("camera") == null ? "すべて" : getIntent().getStringExtra("camera"));
        List<PhotoRepository.Photo> local = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        Map<String, String> storedIds = store.photoIds();
        for (PhotoRepository.Photo photo : snapshot.photos) {
          if (!"content".equals(photo.uri.getScheme())) continue;
          local.add(photo); ids.add(storedIds.get(TagStore.source(photo)));
        }
        TagJudgments loaded = store.load();
        Map<String, AutomaticTags.Decision> inferred = store.automatic(selected.id);
        main.post(() -> {
          if (!current(token)) return;
          photos.clear(); photos.addAll(local); photoIds.clear(); photoIds.addAll(ids); judgments = loaded; automatic = inferred;
          showTag();
        });
      } catch (Exception error) {
        main.post(() -> { if (current(token)) { failure(error); showTags(); } });
      }
    });
  }

  private TagJudgments.Value value(int photo) {
    String id = photoIds.get(photo);
    return id == null ? TagJudgments.Value.UNDECIDED : judgments.value(tag.id, id);
  }

  private boolean isAutomatic(int photo) {
    return value(photo) == TagJudgments.Value.UNDECIDED
        && automatic.containsKey(TagStore.source(photos.get(photo)));
  }

  private void showTag() {
    if (busy) return;
    browseMode = 0;
    stopAnalysis(); reviewing = false;
    int token = screen(tag.name);
    root.addView(text("タグを読み込み中…", 14));
    String tagId = tag.id;
    worker.execute(() -> {
      try (TagStore store = new TagStore(this)) {
        TagJudgments loaded = store.load();
        Map<String, AutomaticTags.Decision> inferred = store.automatic(tagId);
        Map<String, String> ids = store.photoIds();
        main.post(() -> {
          if (!current(token)) return;
          judgments = loaded; automatic = inferred;
          for (int i = 0; i < photos.size(); i++) photoIds.set(i, ids.get(TagStore.source(photos.get(i))));
          renderTag();
        });
      } catch (Exception error) { main.post(() -> { if (current(token)) failure(error); }); }
    });
  }

  private String scope() {
    String folder = getIntent().getStringExtra("folder"), camera = getIntent().getStringExtra("camera");
    return (folder == null ? "すべての写真" : folder) + (camera == null || camera.equals("すべて") ? "" : " · " + camera);
  }

  private void renderTag() {
    updateAutomaticCount(); screen(tag.name);
    int assigned = 0;
    for (int i = 0; i < photos.size(); i++) if (value(i) == TagJudgments.Value.ASSIGNED) assigned++;
    root.addView(text(scope(), 13));
    root.addView(text("タグ付き " + (assigned + automaticCount) + "枚", 22));
    LinearLayout previews = new LinearLayout(this);
    previews.setOrientation(LinearLayout.HORIZONTAL);
    root.addView(previews, new LinearLayout.LayoutParams(-1, 0, 1));
    int token = generation;
    for (int i = 0, count = 0; i < photos.size() && count < 3; i++) {
      if (value(i) != TagJudgments.Value.ASSIGNED && !isAutomatic(i)) continue;
      count++; PhotoRepository.Photo photo = photos.get(i);
      android.widget.ImageView image = new android.widget.ImageView(this);
      image.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
      previews.addView(image, new LinearLayout.LayoutParams(0, -1, 1));
      image.setContentDescription(photo.name + "、タグ付きの写真を見る");
      image.setOnClickListener(v -> showPhotos(Filter.ATTACHED));
      worker.execute(() -> {
        try { Bitmap bitmap = PhotoRepository.bitmap(new PhotoRepository(this).preview(photo), 600);
          main.post(() -> { if (current(token)) image.setImageBitmap(bitmap); else bitmap.recycle(); });
        } catch (Exception ignored) { }
      });
    }
    int controlsStart = root.getChildCount();
    root.addView(button("写真を見る", () -> showPhotos(Filter.ATTACHED)));
    root.addView(button("写真を追加", () -> showPhotos(Filter.ALL)));
    Button search = button("自動で探す", this::startAutomatic);
    search.setEnabled(assigned > 0); root.addView(search);
    root.addView(text(assigned > 0 ? "付けた写真と外した写真を参考に、似た写真へタグを付けます。訂正をほかの写真にも反映するには、もう一度実行してください。"
        : "「写真を追加」で見本にタグを付けると、自動で探せます。", 13));
    root.addView(button("その他", () -> new GalleryUi.Sheet(this).setTitle("その他")
        .setItems(new String[]{"自動で付いた写真", "外した写真", "自動で付いたタグをすべて外す"}, (d, which) -> {
          if (which == 0) showPhotos(Filter.AUTOMATIC);
          else if (which == 1) showPhotos(Filter.REMOVED); else clearAutomatic();
        }).show()));
    root.addView(button("タグ一覧に戻る", this::showTags));
    GalleryUi.bottomControls(root, controlsStart);
  }

  private void showPhotos(Filter filter) {
    if (busy) return;
    selecting = false; selectedPhotos.clear();
    renderPhotos(filter, 0, 0);
  }

  private void renderPhotos(Filter filter, int scrollPosition, int scrollOffset) {
    if (busy) return;
    stopAnalysis(); reviewing = true; browseMode = 1; browseFilter = filter;
    browsePhotos.clear();
    for (int i = 0; i < photos.size(); i++) {
      boolean attached = isAutomatic(i) || value(i) == TagJudgments.Value.ASSIGNED;
      if (filter == Filter.ALL || filter == Filter.ATTACHED && attached
          || filter == Filter.AUTOMATIC && isAutomatic(i)
          || filter == Filter.REMOVED && value(i) == TagJudgments.Value.EXCLUDED) browsePhotos.add(i);
    }
    String title = filter == Filter.ALL ? "写真を追加" : filter == Filter.REMOVED ? "外した写真"
        : filter == Filter.AUTOMATIC ? "自動で付いた写真" : "タグ付きの写真";
    int token = screen(tag.name + " · " + title);
    root.addView(text(scope() + " · " + browsePhotos.size() + "枚", 14));
    selectionButton = button(selecting ? "選択を終了" : "選択", () -> {
      if (busy) return;
      selecting = !selecting; selectedPhotos.clear(); refreshSelection();
    });

    android.widget.GridView grid = new android.widget.GridView(this); photoGrid = grid;
    grid.setNumColumns(4); grid.setHorizontalSpacing(GalleryUi.dp(this, 2)); grid.setVerticalSpacing(GalleryUi.dp(this, 2));
    photoAdapter = new android.widget.BaseAdapter() {
      public int getCount() { return browsePhotos.size(); }
      public Object getItem(int position) { return photos.get(browsePhotos.get(position)); }
      public long getItemId(int position) { return position; }
      public android.view.View getView(int position, android.view.View recycled, android.view.ViewGroup parent) {
        ThumbnailCell cell = recycled instanceof ThumbnailCell ? (ThumbnailCell) recycled : new ThumbnailCell(TagActivity.this);
        int index = browsePhotos.get(position);
        PhotoRepository.Photo photo = photos.get(index);
        boolean reload = cell.image.getTag() != photo;
        Object binding = reload ? new Object() : cell.getTag();
        if (reload) { cell.setTag(binding); cell.image.setTag(photo); cell.image.setImageDrawable(null); }
        cell.badges.rank = isAutomatic(index) ? "タグあり · 自動" : value(index) == TagJudgments.Value.ASSIGNED ? "タグあり" : "タグなし"; cell.badges.invalidate();
        cell.badges.selectionMode = selecting;
        cell.badges.selected = selectedPhotos.contains(index);
        cell.setContentDescription(photo.name + " · " + cell.badges.rank + (selecting ? (selectedPhotos.contains(index) ? " · 選択済み" : " · 未選択") : ""));
        if (reload) worker.execute(() -> {
          if (!current(token)) return;
          try {
            Bitmap bitmap = PhotoRepository.bitmap(new PhotoRepository(TagActivity.this).preview(photo), 360);
            main.post(() -> {
              if (!current(token) || cell.getTag() != binding) { bitmap.recycle(); return; }
              cell.image.setImageBitmap(bitmap);
            });
          } catch (Exception error) {
            main.post(() -> { if (current(token) && cell.getTag() == binding) { cell.badges.rank = "読込失敗"; cell.badges.invalidate(); } });
          }
        });
        return cell;
      }
    };
    grid.setAdapter(photoAdapter);
    grid.setOnItemClickListener((parent, view, position, id) -> {
      if (busy) return;
      if (selecting) toggleSelection(position); else showAssignedPhoto(position);
    });
    grid.setOnItemLongClickListener((parent, view, position, id) -> {
      if (!busy) { selecting = true; toggleSelection(position); }
      return true;
    });
    root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
    if (browsePhotos.isEmpty()) root.addView(text("この範囲に該当する写真はありません", 16));
    int controlsStart = root.getChildCount();
    root.addView(selectionButton);
    batchButton = button("", this::changeSelected);
    root.addView(batchButton); refreshSelection();
    root.addView(button("タグに戻る", this::showTag));
    GalleryUi.bottomControls(root, controlsStart);
    grid.setSelectionFromTop(scrollPosition, scrollOffset);
  }

  private void toggleSelection(int position) {
    int index = browsePhotos.get(position);
    if (!selectedPhotos.add(index)) selectedPhotos.remove(index);
    refreshSelection();
  }

  private void refreshSelection() {
    selectionButton.setText(selecting ? "選択を終了" : "選択");
    batchButton.setVisibility(selecting ? android.view.View.VISIBLE : android.view.View.GONE);
    boolean remove = browseFilter == Filter.ATTACHED || browseFilter == Filter.AUTOMATIC;
    batchButton.setText(busy ? "保存中…" : selectedPhotos.size() + (remove ? "枚のタグを外す" : "枚にタグを付ける"));
    batchButton.setEnabled(!busy && !selectedPhotos.isEmpty()); selectionButton.setEnabled(!busy);
    photoAdapter.notifyDataSetChanged();
  }

  private void changeSelected() {
    if (busy || selectedPhotos.isEmpty()) return;
    List<Integer> selected = new ArrayList<>(selectedPhotos);
    int first = photoGrid.getFirstVisiblePosition();
    int anchor = browsePhotos.get(Math.min(first, browsePhotos.size() - 1));
    int offset = photoGrid.getChildCount() == 0 ? 0 : photoGrid.getChildAt(0).getTop();
    String tagId = tag.id, operation = UUID.randomUUID().toString();
    TagJudgments baseline = judgments;
    TagJudgments.Value value = browseFilter == Filter.ATTACHED || browseFilter == Filter.AUTOMATIC
        ? TagJudgments.Value.EXCLUDED : TagJudgments.Value.ASSIGNED;
    int token = generation; busy = true; refreshSelection();
    worker.execute(() -> {
      try (TagStore store = new TagStore(this)) {
        Map<String, Long> expected = new java.util.LinkedHashMap<>();
        Map<Integer, String> ids = new HashMap<>();
        for (int index : selected) {
          String id = store.photoId(photos.get(index), true); ids.put(index, id);
          TagJudgments.Entry entry = baseline.latest(tagId, id);
          expected.put(id, entry == null ? 0L : entry.revision);
        }
        TagJudgments saved = store.recordBatch(operation, tagId, expected, value);
        Map<String, AutomaticTags.Decision> inferred = store.automatic(tagId);
        main.post(() -> {
          busy = false; if (!current(token)) return;
          judgments = saved; automatic = inferred;
          for (Map.Entry<Integer, String> entry : ids.entrySet()) photoIds.set(entry.getKey(), entry.getValue());
          selectedPhotos.clear();
          int target = 0;
          for (int index : browsePhotos) {
            if (index >= anchor) break;
            if (value != TagJudgments.Value.EXCLUDED || !selected.contains(index)) target++;
          }
          renderPhotos(browseFilter, target, offset);
          Toast.makeText(this, selected.size() + (value == TagJudgments.Value.EXCLUDED ? "枚のタグを外しました" : "枚にタグを付けました"), Toast.LENGTH_SHORT).show();
        });
      } catch (Exception error) {
        main.post(() -> { busy = false; if (current(token)) { refreshSelection(); failure(error); } });
      }
    });
  }

  private void showAssignedPhoto(int position) {
    if (busy) return;
    browseMode = 2;
    int index = browsePhotos.get(position);
    PhotoRepository.Photo photo = photos.get(index);
    int token = screen(tag.name);
    root.addView(text((position + 1) + " / " + browsePhotos.size() + " · " + photo.name, 14));
    boolean attached = isAutomatic(index) || value(index) == TagJudgments.Value.ASSIGNED;
    root.addView(text(attached ? (isAutomatic(index) ? "タグあり · 自動で付けました" : "タグあり · 自分で付けました") : "タグなし", 12));
    ZoomImageView image = new ZoomImageView(this);
    root.addView(image, new LinearLayout.LayoutParams(-1, 0, 1));
    int controlsStart = root.getChildCount();
    root.addView(button("拡大・戻す", image::toggleZoom));
    Runnable previous = () -> { if (position > 0) showAssignedPhoto(position - 1); };
    Runnable next = () -> { if (position + 1 < browsePhotos.size()) showAssignedPhoto(position + 1); };
    image.setPageListener(direction -> { if (direction > 0) next.run(); else previous.run(); });
    LinearLayout navigation = new LinearLayout(this);
    Button before = button("前の写真", previous), after = button("次の写真", next);
    before.setEnabled(position > 0); after.setEnabled(position + 1 < browsePhotos.size());
    navigation.addView(before, new LinearLayout.LayoutParams(0, -2, 1));
    navigation.addView(after, new LinearLayout.LayoutParams(0, -2, 1)); root.addView(navigation);
    root.addView(button(attached ? "タグを外す" : "タグを付ける",
        () -> changeTag(index, position, attached ? TagJudgments.Value.EXCLUDED : TagJudgments.Value.ASSIGNED)));
    root.addView(button("一覧に戻る", () -> showPhotos(browseFilter)));
    GalleryUi.bottomControls(root, controlsStart);
    worker.execute(() -> {
      try {
        Bitmap bitmap = PhotoRepository.bitmap(new PhotoRepository(this).preview(photo), 1600);
        main.post(() -> { if (current(token)) image.setBitmap(bitmap); else bitmap.recycle(); });
      } catch (Exception error) { main.post(() -> { if (current(token)) failure(error); }); }
    });
  }

  private void clearAutomatic() {
    new GalleryUi.Sheet(this).setTitle("自動で付いたタグをすべて外しますか？")
        .setMessage("「" + tag.name + "」が対象です。全アルバムの自動で付いた分を外します。自分で付けたタグは残ります。")
        .setPositiveButton("すべて外す", (d, w) -> {
          stopAnalysis(); busy = true;
          String tagId = tag.id;
          worker.execute(() -> {
            try (TagStore store = new TagStore(this)) {
              store.automation(tagId, false, true);
              main.post(() -> { busy = false; if (!isDestroyed()) showTag(); });
            } catch (Exception error) { main.post(() -> { busy = false; failure(error); }); }
          });
        }).setNegativeButton("外さない", null).show();
  }

  private void changeTag(int index, int position, TagJudgments.Value value) {
    if (busy) return;
    busy = true;
    int token = generation;
    String tagId = tag.id, id = photoIds.get(index);
    TagJudgments.Entry entry = id == null ? null : judgments.latest(tagId, id);
    long revision = entry == null ? 0 : entry.revision;
    worker.execute(() -> {
      try (TagStore store = new TagStore(this)) {
        String savedId = store.photoId(photos.get(index), true);
        TagJudgments saved = store.record(UUID.randomUUID().toString(), tagId, savedId, revision, value);
        Map<String, AutomaticTags.Decision> inferred = store.automatic(tagId);
        main.post(() -> {
          busy = false; if (!current(token)) return;
          photoIds.set(index, savedId); judgments = saved; automatic = inferred;
          showAssignedPhoto(position);
        });
      } catch (Exception error) { main.post(() -> { busy = false; if (current(token)) failure(error); }); }
    });
  }

  private void stopAnalysis() {
    if (autoRunning && tag != null) {
      String tagId = tag.id;
      autoRunning = false;
      worker.execute(() -> {
        try (TagStore store = new TagStore(this)) { store.automation(tagId, false, false); }
        catch (Exception error) { main.post(() -> { if (!isDestroyed()) failure(error); }); }
      });
    }
    autoPending = false; autoAgain = false;
    analysisGeneration++;
    if (analyzing != null) analyzing.cancel(false);
    analysisActive = false;
    related = false; analysisStatus = null;
  }

  private List<TagJudgments.Value> labels() {
    List<TagJudgments.Value> result = new ArrayList<>();
    for (int i = 0; i < photos.size(); i++) result.add(value(i));
    return result;
  }

  private void updateAutomaticCount() {
    automaticCount = 0;
    for (int i = 0; i < photos.size(); i++) if (isAutomatic(i)) automaticCount++;
  }

  private String progress() {
    if (!automaticError.isEmpty()) return automaticError;
    return "自動付与 " + automaticCount + "枚 · " + (autoPending ? "保存中 · " : "") + "解析 " + analyzed + " / " + photos.size() + "枚 · 取得失敗 " + analysisFailures + "枚"
        + (analysisFinished && !autoPending ? " · 完了" : " · 解析中も確認できます");
  }

  private void updateProgress() {
    if (analysisStatus != null) analysisStatus.setText(progress());
    boolean done = analysisFinished && !autoPending && !autoAgain && automaticError.isEmpty();
    if (jobTitle != null) jobTitle.setText(!automaticError.isEmpty() ? "処理を続けられませんでした" : done ? "自動処理が完了しました" : "自動処理中");
    if (resultButton != null) resultButton.setText(done ? "結果を見る" : "停止して写真を見る");
  }

  private void startRelated() {
    stopAnalysis();
    related = true;
    beginAnalysis();
  }

  private void startAutomatic() {
    stopAnalysis();
    busy = true;
    int token = generation;
    String tagId = tag.id;
    worker.execute(() -> {
      try (TagStore store = new TagStore(this)) {
        long epoch = store.automation(tagId, true, false);
        main.post(() -> {
          busy = false;
          if (!current(token)) return;
          automaticError = ""; autoEpoch = epoch; autoRunning = true; related = true;
          beginAnalysis();
          showAutomaticProgress();
        });
      } catch (Exception error) { main.post(() -> { busy = false; if (current(token)) failure(error); }); }
    });
  }

  private void showAutomaticProgress() {
    reviewing = true; browseMode = 0;
    screen(tag.name);
    jobTitle = text("自動処理中", 20); root.addView(jobTitle);
    root.addView(text("一枚ずつ判断しなくてもタグが付きます。間違いを含む可能性がある結果として保存します。", 14));
    analysisStatus = text(progress(), 16); root.addView(analysisStatus);
    root.addView(text(scope(), 13));
    root.addView(new android.view.View(this), new LinearLayout.LayoutParams(-1, 0, 1));
    resultButton = button("停止して写真を見る", this::loadResults);
    root.addView(resultButton);
  }

  private void loadResults() {
    if (busy) return;
    stopAnalysis(); busy = true;
    String tagId = tag.id;
    int token = generation;
    worker.execute(() -> {
      try (TagStore store = new TagStore(this)) {
        Map<String, AutomaticTags.Decision> saved = store.automatic(tagId);
        Map<String, String> ids = store.photoIds();
        main.post(() -> {
          busy = false; if (!current(token)) return;
          automatic = saved;
          for (int i = 0; i < photos.size(); i++) photoIds.set(i, ids.get(TagStore.source(photos.get(i))));
          showPhotos(Filter.ATTACHED);
        });
      } catch (Exception error) { main.post(() -> { busy = false; if (current(token)) failure(error); }); }
    });
  }

  private void requestAutomatic() {
    if (!autoRunning || busy) return;
    if (autoPending) { autoAgain = true; return; }
    autoPending = true; autoAgain = false;
    int token = analysisGeneration;
    long epoch = autoEpoch;
    String tagId = tag.id;
    List<PhotoRepository.Photo> sources = new ArrayList<>(photos);
    Map<Integer, RelatedPhotos.Vector> vectors = new HashMap<>(features);
    worker.execute(() -> {
      try (TagStore store = new TagStore(this)) {
        long revision = store.judgmentRevision(tagId);
        TagJudgments human = store.load();
        // A concurrent human update either causes applyAutomatic to reject, or is read consistently.
        Map<String, String> ids = store.photoIds();
        List<TagJudgments.Value> values = new ArrayList<>();
        Set<Integer> blocked = new HashSet<>();
        for (int i = 0; i < sources.size(); i++) {
          String id = ids.get(TagStore.source(sources.get(i)));
          TagJudgments.Entry entry = id == null ? null : human.latest(tagId, id);
          values.add(entry == null ? TagJudgments.Value.UNDECIDED : entry.value);
          if (entry != null && entry.value == TagJudgments.Value.UNDECIDED) blocked.add(i);
        }
        Map<Integer, AutomaticTags.Decision> predictions = AutomaticTags.predict(vectors, values, blocked);
        boolean applied = store.applyAutomatic(tagId, epoch, revision, sources, vectors.keySet(), predictions,
            () -> token != analysisGeneration);
        Map<String, AutomaticTags.Decision> saved = store.automatic(tagId);
        Map<String, String> savedIds = store.photoIds();
        main.post(() -> {
          if (isDestroyed() || token != analysisGeneration || !autoRunning) return;
          autoPending = false;
          if (applied) {
            automatic = saved;
            for (int i = 0; i < photos.size(); i++) photoIds.set(i, savedIds.get(TagStore.source(photos.get(i))));
          }
          updateAutomaticCount();
          automaticError = applied ? "" : "判断が更新されたため保存を見送りました。再実行してください。";
          updateProgress();
          if (autoAgain) requestAutomatic();
        });
      } catch (Exception error) {
        main.post(() -> {
          if (isDestroyed() || token != analysisGeneration) return;
          autoPending = false; failure(error);
          automaticError = "自動付与を保存できませんでした。終了して再試行してください。";
          updateProgress();
        });
      }
    });
  }

  private void beginAnalysis() {
    int epoch = ++analysisGeneration;
    if (analyzing != null) analyzing.cancel(false);
    features.clear(); processed.clear(); analyzed = 0; analysisFailures = 0; analysisFinished = false;
    analysisActive = false; analysisPaused = false;
    resumeAnalysis();
  }

  private void resumeAnalysis() {
    if (analysisActive || analysisPaused || analysisFinished || !related) return;
    analysisActive = true;
    int epoch = analysisGeneration;
    List<PhotoRepository.Photo> sources = new ArrayList<>(photos);
    List<TagJudgments.Value> initialLabels = labels();
    // Examples first, then evenly interleave the entire date-ordered scope.
    Set<Integer> order = new LinkedHashSet<>();
    for (int i = 0; i < sources.size(); i++)
      if (initialLabels.get(i) != TagJudgments.Value.UNDECIDED) order.add(i);
    int stride = Math.max(1, (sources.size() + 31) / 32);
    for (int offset = 0; offset < stride; offset++)
      for (int i = offset; i < sources.size(); i += stride) order.add(i);
    order.removeAll(processed);
    analyzing = analysis.submit(() -> {
      try (PhotoFeatures extractor = new PhotoFeatures(this)) {
        for (int i : order) {
          if (epoch != analysisGeneration || analysisPaused || Thread.currentThread().isInterrupted()) return;
          RelatedPhotos.Vector vector = null;
          try { vector = extractor.obtain(sources.get(i)); }
          catch (Exception error) { /* Missing/failed analysis is not a negative judgment. */ }
          RelatedPhotos.Vector completed = vector;
          main.post(() -> {
            if (isDestroyed() || epoch != analysisGeneration) return;
            processed.add(i); analyzed = processed.size();
            if (completed == null) analysisFailures++; else features.put(i, completed);
            updateProgress();
            if (autoRunning && analyzed % 128 == 0) requestAutomatic();
          });
        }
      } catch (Exception error) {
        main.post(() -> { if (!isDestroyed() && epoch == analysisGeneration) { analysisPaused = true; automaticError = "解析を続けられませんでした。終了して再試行してください。"; failure(error); } });
      } finally {
        main.post(() -> {
          if (isDestroyed() || epoch != analysisGeneration) return;
          analysisActive = false;
          analysisFinished = processed.size() == photos.size();
          if (autoRunning) requestAutomatic();
          updateProgress();
          if (!analysisPaused && !analysisFinished) resumeAnalysis();
        });
      }
    });
  }

  public void onBackPressed() {
    DiagnosticLog.event("action.back", 0, "screen", "TagActivity");
    if (busy) return;
    if (browseMode == 1 && selecting) { selecting = false; selectedPhotos.clear(); refreshSelection(); }
    else if (browseMode == 2) showPhotos(browseFilter);
    else if (reviewing) showTag(); else super.onBackPressed();
  }

  protected void onDestroy() {
    stopAnalysis(); generation++; worker.shutdown(); analysis.shutdownNow(); super.onDestroy();
  }

  protected void onStop() {
    // Finish the current image, then pause at a safe boundary. Do not interrupt native inference.
    analysisPaused = true;
    super.onStop();
  }

  protected void onRestart() {
    super.onRestart();
    analysisPaused = false;
    if (related) {
      resumeAnalysis();
      updateProgress();
    }
  }
}
