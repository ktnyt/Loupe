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
public final class TagActivity extends Activity {
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final List<PhotoRepository.Photo> photos = new ArrayList<>();
  private final List<String> photoIds = new ArrayList<>();
  private final List<Integer> queue = new ArrayList<>();
  private final List<Step> steps = new ArrayList<>();
  private final ExecutorService analysis = Executors.newSingleThreadExecutor();
  private final Map<Integer, RelatedPhotos.Vector> features = new HashMap<>();
  private Future<?> analyzing;
  private volatile int analysisGeneration;
  private boolean related, waiting, rankingPending, analysisFinished, expandAfterAssignment;
  private int analyzed, analysisFailures;
  private TextView analysisStatus, originStatus;
  private String automaticError = "";
  private String candidateReason = "";
  private Map<String, AutomaticTags.Decision> automatic = new HashMap<>();
  private boolean autoRunning, autoPending, autoAgain;
  private long autoEpoch;
  private TagJudgments judgments = new TagJudgments();
  private TagStore.Tag tag;
  private LinearLayout root;
  private int generation, position;
  private boolean busy, reviewing;

  private static final class Step {
    final int position;
    final String operation;
    Step(int position, String operation) { this.position = position; this.operation = operation; }
  }

  public void onCreate(Bundle state) {
    super.onCreate(state);
    showTags();
  }

  private TextView text(String value, int size) {
    TextView view = new TextView(this);
    view.setText(value); view.setTextSize(size); view.setTextColor(GalleryUi.TEXT);
    view.setPadding(20, 12, 20, 12);
    return view;
  }

  private Button button(String title, Runnable action) { return GalleryUi.button(this, title, action); }

  private int screen(String title) {
    generation++;
    originStatus = null;
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
    dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
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
    }));
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

  private void renderTag() {
    screen(tag.name);
    int assigned = 0, excluded = 0, inferred = 0;
    for (int i = 0; i < photos.size(); i++) {
      if (value(i) == TagJudgments.Value.ASSIGNED) assigned++;
      if (value(i) == TagJudgments.Value.EXCLUDED) excluded++;
      if (isAutomatic(i)) inferred++;
    }
    root.addView(text("付与済み " + (assigned + inferred) + "枚（手動 " + assigned + " · 自動 " + inferred
        + "）\n該当しない " + excluded + "枚 · 未付与 " + (photos.size() - assigned - inferred - excluded) + "枚", 16));
    root.addView(text(assigned > 0
        ? "見本から広く自動付与します。誤りは訂正でき、自動分だけ取り消せます。"
        : "まず一枚にタグを付けると、残りにも自動でタグを広げます。", 13));
    root.addView(button(assigned > 0 ? "自動でタグを広げる" : "見本の写真にタグを付ける",
        assigned > 0 ? () -> startAutomatic(true, false) : this::startSeedReview));
    root.addView(button("付与済みの写真を見る・訂正", () -> startReview(true)));
    root.addView(button("自動付与した写真だけ見る", this::reviewAutomatic));
    root.addView(button("すべての未付与写真を順に確認", () -> startReview(false)));
    root.addView(button("除外した写真を見直す", () -> startExcluded()));
    root.addView(button("自動付与をすべて取り消す", this::clearAutomatic));
    root.addView(button("タグ一覧", this::showTags));
  }

  private void reviewAutomatic() {
    stopAnalysis(); expandAfterAssignment = false;
    queue.clear(); steps.clear(); position = 0;
    for (int i = 0; i < photos.size(); i++) if (isAutomatic(i)) queue.add(i);
    showReview();
  }

  private void clearAutomatic() {
    new GalleryUi.Sheet(this).setTitle("自動付与を取り消す")
        .setMessage("このタグの全アルバムの自動付与を取り消します。手動で付けたタグは残ります。")
        .setPositiveButton("取り消す", (d, w) -> {
          stopAnalysis(); busy = true;
          String tagId = tag.id;
          worker.execute(() -> {
            try (TagStore store = new TagStore(this)) {
              store.automation(tagId, false, true);
              main.post(() -> { busy = false; if (!isDestroyed()) showTag(); });
            } catch (Exception error) { main.post(() -> { busy = false; failure(error); }); }
          });
        }).setNegativeButton("戻る", null).show();
  }

  private void startSeedReview() {
    startReview(false);
    expandAfterAssignment = true;
  }

  private void startReview(boolean assignedOnly) {
    stopAnalysis();
    expandAfterAssignment = false;
    queue.clear(); steps.clear(); position = 0;
    String seed = getIntent().getStringExtra("photo");
    for (int i = 0; i < photos.size(); i++) {
      boolean assigned = value(i) == TagJudgments.Value.ASSIGNED || isAutomatic(i);
      if (assignedOnly ? !assigned : (assigned || value(i) != TagJudgments.Value.UNDECIDED)) continue;
      if (photos.get(i).uri.toString().equals(seed)) queue.add(0, i); else queue.add(i);
    }
    showReview();
  }

  private void startExcluded() {
    stopAnalysis();
    expandAfterAssignment = false;
    queue.clear(); steps.clear(); position = 0;
    for (int i = 0; i < photos.size(); i++) if (value(i) == TagJudgments.Value.EXCLUDED) queue.add(i);
    showReview();
  }

  private void showReview() {
    reviewing = true;
    waiting = false;
    int token = screen(tag.name);
    if (position >= queue.size()) {
      root.addView(text("この範囲の確認が終わりました", 18));
      root.addView(button("一つ戻す", this::undo));
      root.addView(button("終了して結果を見る", this::showTag));
      return;
    }
    int photoIndex = queue.get(position);
    PhotoRepository.Photo photo = photos.get(photoIndex);
    originStatus = text(origin(photoIndex), 12); root.addView(originStatus);
    root.addView(text((related ? "候補 " + (position + 1) : (position + 1) + " / " + queue.size()) + " · " + photo.name, 13));
    if (related) {
      root.addView(text(candidateReason, 13));
      analysisStatus = text(progress(), 12); root.addView(analysisStatus);
    }
    ZoomImageView image = new ZoomImageView(this);
    root.addView(image, new LinearLayout.LayoutParams(-1, 0, 1));
    TextView status = text("写真を読み込み中…", 13); root.addView(status);
    LinearLayout controls = new LinearLayout(this);
    Button pass = button("← パス", this::pass);
    Button assign = button("付与 →", () -> record(TagJudgments.Value.ASSIGNED));
    Button exclude = button("該当しない", () -> record(TagJudgments.Value.EXCLUDED));
    pass.setEnabled(false); assign.setEnabled(false); exclude.setEnabled(false);
    controls.addView(pass, new LinearLayout.LayoutParams(0, -2, 1));
    controls.addView(assign, new LinearLayout.LayoutParams(0, -2, 1));
    root.addView(controls); root.addView(exclude);
    Button clear = button("判断を取り消す", () -> record(TagJudgments.Value.UNDECIDED));
    clear.setEnabled(false); root.addView(clear);
    root.addView(button("一つ戻す", this::undo)); root.addView(button("終了して結果を見る", this::showTag));
    worker.execute(() -> {
      try {
        PhotoRepository repository = new PhotoRepository(this);
        Bitmap bitmap = PhotoRepository.bitmap(repository.preview(photo), 1600);
        main.post(() -> {
          if (!current(token)) { bitmap.recycle(); return; }
          image.setBitmap(bitmap);
          status.setText(autoRunning ? "左: 提示をパス（自動付与は継続） · 右: 手動確定" : "左: パス · 右: 付与（保存後に次へ）");
          pass.setEnabled(true); assign.setEnabled(true); exclude.setEnabled(true); clear.setEnabled(true);
          image.setPageListener(direction -> { if (direction > 0) pass(); else record(TagJudgments.Value.ASSIGNED); });
        });
      } catch (Exception error) {
        main.post(() -> {
          if (!current(token)) return;
          status.setText("写真を取得できません。パスするか、終了して開き直してください。"); pass.setEnabled(true);
        });
      }
    });
  }

  private void pass() {
    if (busy || position >= queue.size()) return;
    steps.add(new Step(position, null)); position++; advance();
  }

  private void record(TagJudgments.Value value) {
    if (busy || position >= queue.size()) return;
    boolean restartAutomatic = autoRunning || !automatic.isEmpty() || expandAfterAssignment;
    busy = true;
    int token = generation, previousPosition = position, photoIndex = queue.get(position);
    String operation = UUID.randomUUID().toString(), tagId = tag.id;
    String knownId = photoIds.get(photoIndex);
    TagJudgments.Entry current = knownId == null ? null : judgments.latest(tagId, knownId);
    long expected = current == null ? 0 : current.revision;
    PhotoRepository.Photo photo = photos.get(photoIndex);
    worker.execute(() -> {
      try (TagStore store = new TagStore(this)) {
        String id = store.photoId(photo, true);
        TagJudgments saved = store.record(operation, tagId, id, expected, value);
        Map<String, AutomaticTags.Decision> inferred = store.automatic(tagId);
        main.post(() -> {
          busy = false;
          if (!current(token)) return;
          photoIds.set(photoIndex, id); judgments = saved; automatic = inferred;
          steps.add(new Step(previousPosition, operation)); position++;
          if (restartAutomatic && !autoRunning) { startAutomatic(false, true); return; }
          if (autoRunning) requestAutomatic();
          advance();
        });
      } catch (Exception error) {
        main.post(() -> { busy = false; if (current(token)) failure(error); });
      }
    });
  }

  private String origin(int index) {
    return isAutomatic(index) ? "自動付与 · 推定結果です。誤りは訂正できます"
        : value(index) == TagJudgments.Value.ASSIGNED ? "手動で付与済み" : "未付与／確認中";
  }

  private void undo() {
    if (busy || steps.isEmpty()) return;
    boolean restartAutomatic = autoRunning || !automatic.isEmpty();
    Step step = steps.get(steps.size() - 1);
    if (step.operation == null) {
      candidateReason = "一つ戻した写真";
      steps.remove(steps.size() - 1); position = step.position; showReview(); return;
    }
    busy = true;
    int token = generation;
    worker.execute(() -> {
      try (TagStore store = new TagStore(this)) {
        TagJudgments saved = store.undo(UUID.randomUUID().toString(), step.operation);
        Map<String, AutomaticTags.Decision> inferred = store.automatic(tag.id);
        main.post(() -> {
          busy = false; if (!current(token)) return;
          judgments = saved; automatic = inferred;
          if (autoRunning) requestAutomatic();
          steps.remove(steps.size() - 1); position = step.position;
          candidateReason = "一つ戻した写真";
          if (restartAutomatic && !autoRunning) { startAutomatic(false, true); return; }
          showReview();
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
    if (analyzing != null) analyzing.cancel(true);
    related = false; waiting = false; rankingPending = false; analysisStatus = null;
  }

  private List<TagJudgments.Value> labels() {
    List<TagJudgments.Value> result = new ArrayList<>();
    for (int i = 0; i < photos.size(); i++) result.add(value(i));
    return result;
  }

  private String progress() {
    int count = 0;
    for (int i = 0; i < photos.size(); i++) if (isAutomatic(i)) count++;
    if (!automaticError.isEmpty()) return automaticError;
    return "自動付与 " + count + "枚 · " + (autoPending ? "保存中 · " : "") + "解析 " + analyzed + " / " + photos.size() + "枚 · 取得失敗 " + analysisFailures + "枚"
        + (analysisFinished && !autoPending ? " · 完了" : " · 解析中も確認できます");
  }

  private void startRelated() {
    stopAnalysis();
    queue.clear(); steps.clear(); position = 0;
    related = true;
    beginAnalysis(); advance();
  }

  private void startAutomatic(boolean reset, boolean review) {
    stopAnalysis();
    if (reset) { queue.clear(); steps.clear(); position = 0; }
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
          if (review) advance(); else showAutomaticProgress();
        });
      } catch (Exception error) { main.post(() -> { busy = false; if (current(token)) failure(error); }); }
    });
  }

  private void showAutomaticProgress() {
    reviewing = true; waiting = false;
    screen(tag.name);
    root.addView(text("見本から自動でタグを広げています", 20));
    root.addView(text("一枚ずつ判断しなくても付与されます。誤付与を含む推定結果として保存します。", 14));
    analysisStatus = text(progress(), 16); root.addView(analysisStatus);
    root.addView(button("候補を判断して調整", this::advance));
    root.addView(button("停止して付与済みを見る", this::showTag));
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
          automaticError = applied ? "" : "判断が更新されたため保存を見送りました。再実行してください。";
          if (originStatus != null && position < queue.size()) originStatus.setText(origin(queue.get(position)));
          if (analysisStatus != null) analysisStatus.setText(progress());
          if (autoAgain) requestAutomatic();
        });
      } catch (Exception error) {
        main.post(() -> {
          if (isDestroyed() || token != analysisGeneration) return;
          autoPending = false; failure(error);
          automaticError = "自動付与を保存できませんでした。終了して再試行してください。";
          if (analysisStatus != null) analysisStatus.setText(progress());
        });
      }
    });
  }

  private void beginAnalysis() {
    int epoch = ++analysisGeneration;
    if (analyzing != null) analyzing.cancel(true);
    features.clear(); analyzed = 0; analysisFailures = 0; analysisFinished = false;
    List<PhotoRepository.Photo> sources = new ArrayList<>(photos);
    List<TagJudgments.Value> initialLabels = labels();
    // Examples first, then evenly interleave the entire date-ordered scope.
    Set<Integer> order = new LinkedHashSet<>();
    for (int i = 0; i < sources.size(); i++)
      if (initialLabels.get(i) != TagJudgments.Value.UNDECIDED) order.add(i);
    int stride = Math.max(1, (sources.size() + 31) / 32);
    for (int offset = 0; offset < stride; offset++)
      for (int i = offset; i < sources.size(); i += stride) order.add(i);
    analyzing = analysis.submit(() -> {
      try (PhotoFeatures extractor = new PhotoFeatures(this)) {
        for (int i : order) {
          if (epoch != analysisGeneration || Thread.currentThread().isInterrupted()) return;
          RelatedPhotos.Vector vector = null;
          try { vector = extractor.obtain(sources.get(i)); }
          catch (Exception error) { /* Missing/failed analysis is not a negative judgment. */ }
          RelatedPhotos.Vector completed = vector;
          main.post(() -> {
            if (isDestroyed() || epoch != analysisGeneration) return;
            analyzed++;
            if (completed == null) analysisFailures++; else features.put(i, completed);
            if (analysisStatus != null) analysisStatus.setText(progress());
            if (autoRunning && analyzed % 128 == 0) requestAutomatic();
            if (waiting && !rankingPending) selectCandidate();
          });
        }
      } catch (Exception error) {
        main.post(() -> { if (!isDestroyed() && epoch == analysisGeneration) failure(error); });
      } finally {
        main.post(() -> {
          if (isDestroyed() || epoch != analysisGeneration) return;
          analysisFinished = true;
          if (autoRunning) requestAutomatic();
          if (analysisStatus != null) analysisStatus.setText(progress());
          if (waiting && !rankingPending) selectCandidate();
        });
      }
    });
  }

  private void advance() {
    if (!related) { showReview(); return; }
    reviewing = true; waiting = true; rankingPending = false;
    screen(tag.name);
    root.addView(text("見本と判断から次の候補を探しています", 17));
    analysisStatus = text(progress(), 12); root.addView(analysisStatus);
    root.addView(button("一つ戻す", this::undo));
    root.addView(button("未判断の写真を順に確認", () -> startReview(false)));
    root.addView(button("終了して結果を見る", this::showTag));
    selectCandidate();
  }

  private void selectCandidate() {
    if (!related || !waiting || rankingPending || busy) return;
    if (!analysisFinished && analyzed < Math.min(32, photos.size())) return;
    rankingPending = true;
    int token = generation, featureCount = features.size();
    boolean finished = analysisFinished;
    Map<Integer, RelatedPhotos.Vector> snapshot = new HashMap<>(features);
    List<TagJudgments.Value> decisions = labels();
    Set<Integer> seen = new HashSet<>(queue.subList(0, Math.min(position, queue.size())));
    int reviewed = position;
    worker.execute(() -> {
      RelatedPhotos.Candidate candidate = null;
      try { candidate = RelatedPhotos.next(snapshot, decisions, seen, reviewed); }
      catch (RuntimeException error) { main.post(() -> { if (current(token)) failure(error); }); }
      RelatedPhotos.Candidate result = candidate;
      main.post(() -> {
        if (!current(token) || !related || !waiting) return;
        rankingPending = false;
        if (result == null) {
          if (featureCount != features.size() || finished != analysisFinished) { selectCandidate(); return; }
          if (analysisFinished) analysisStatus.setText(progress()
              + "\n候補がありません。見本を追加するか、取得できなかった写真を再確認してください。");
          return;
        }
        while (queue.size() > position) queue.remove(queue.size() - 1);
        queue.add(result.photo);
        candidateReason = result.exploration ? "見落としを探す候補 · 見本と違う写真も確認"
            : "見本「" + photos.get(result.exemplar).name + "」に近い候補";
        showReview();
      });
    });
  }

  public void onBackPressed() {
    if (busy) return;
    if (reviewing) showTag(); else super.onBackPressed();
  }

  protected void onDestroy() {
    stopAnalysis(); generation++; worker.shutdown(); analysis.shutdownNow(); super.onDestroy();
  }

  protected void onStop() {
    if (related) {
      autoPending = false; autoAgain = false;
      analysisGeneration++;
      if (analyzing != null) analyzing.cancel(true);
    }
    super.onStop();
  }

  protected void onRestart() {
    super.onRestart();
    if (related) beginAnalysis();
  }
}
