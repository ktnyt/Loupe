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
  String tagDatabaseName="tags.db", featureDatabaseName="photo-features.db";
  private TagStore openStore(){return new TagStore(this,tagDatabaseName);}
  private TagScores.Snapshot confidence, pendingConfidence;
  private volatile int learningGeneration;
  private TextView confidenceSummary;
  private Button attachedState;
  private StatusView emptyPhotos;
  private double displayThreshold=TagConfidence.DEFAULT_THRESHOLD, persistedThreshold=TagConfidence.DEFAULT_THRESHOLD;
  private final Set<Integer> teachingSeen=new HashSet<>();
  private final List<String> teachingUndo=new ArrayList<>();
  private int teachingIndex=-1, teachingCount, teachingTotal;
  private TagJudgments indexedJudgments;
  private String indexedTag;
  private final Map<String,TagJudgments.Entry> latestJudgments=new HashMap<>();
  private final List<Button> teachingButtons=new ArrayList<>();
  private StatusView teachingStatus, learningStatus;
  private Button analysisAction;
  private boolean learning;
  private int teachingChoice=-1;
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
  private StatusView analysisStatus, assignmentStatus, failureCount, savingStatus;
  private TextView jobTitle;
  private Button resultButton;
  private String automaticError = "";
  private Map<String, AutomaticTags.Decision> automatic = new HashMap<>();
  private boolean autoRunning, autoPending, autoAgain;
  private long autoEpoch;
  private TagJudgments judgments = new TagJudgments();
  private TagStore.Tag tag;
  private LinearLayout root;
  private volatile int generation;
  private boolean busy, reviewing;
  private PhotoViewerUi photoViewer;
  private Button photoPrevious, photoNext, photoTag;
  private int photoPosition;
  private boolean photoAttached;
  private int browseMode;
  private int tagScrollY;
  private android.os.Parcelable photoGridState;
  private ScrollView tagScroll;
  private boolean selecting, tagOverview;
  private LinearLayout tagActions, selectionActions;
  private final Set<Integer> selectedPhotos = new LinkedHashSet<>();
  private ScrollingGrid photoGrid;
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
    confidenceSummary=null;attachedState=null;emptyPhotos=null;
    photoViewer=null;photoPrevious=null;photoNext=null;photoTag=null;
    analysisStatus = null; assignmentStatus=null;failureCount=null;savingStatus=null;jobTitle = null; resultButton = null;
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
    StatusFeedback.show(this,StatusView.Symbol.FAILED,"保存・読み込みに失敗しました。もう一度お試しください。",()->{if(browseMode==1)showPhotos(browseFilter);else if(tag!=null)showTag();else showTags();},"再読み込み");
  }

  private StatusView state(StatusView.Symbol symbol,String number,String description){StatusView view=new StatusView(this);view.show(symbol,number,description,null,"");return view;}
  private void loading(String description){StatusView view=new StatusView(this);root.addView(view);view.begin(description);}
  private void showTags() {
    if (busy) return;
    stopAnalysis();
    reviewing = false; tag = null; browseMode = 0;
    selecting = false; selectedPhotos.clear();
    int token = screen("タグ");
    root.removeViewAt(0);
    LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
    content.addView(TagListUi.title(this)); TextView scopeLabel=TagListUi.scope(this); scopeLabel.setText(scope()); content.addView(scopeLabel);
    LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);content.addView(list);
    ScrollView scroll = new ScrollView(this); scroll.addView(content);
    tagScroll = scroll;
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    android.view.View line=new android.view.View(this);line.setBackgroundColor(0xff26262a);
    root.addView(line,new LinearLayout.LayoutParams(-1,GalleryUi.dp(this,1)));
    root.addView(TagListUi.footer(this,this::createTag));
    worker.execute(() -> {
      try (TagStore store = openStore()) {
        List<TagStore.Summary> loaded;
        boolean countsAvailable;
        List<TagStore.Tag> names=store.tags();
        try(GalleryIndex index=new GalleryIndex(this)){
          GalleryIndex.Snapshot snapshot=index.load(getIntent().getStringExtra("folder"),getIntent().getStringExtra("camera")==null?"すべて":getIntent().getStringExtra("camera"));
          loaded=store.summaries(snapshot.photos);countsAvailable=true;
        }catch(Exception readFailure){loaded=new ArrayList<>();for(TagStore.Tag name:names)loaded.add(new TagStore.Summary(name));countsAvailable=false;}
        final List<TagStore.Summary> tags=loaded;final boolean haveCounts=countsAvailable;
        main.post(() -> {
          if (!current(token)) return;
          scopeLabel.setText(scope()+" · "+tags.size()+"個");
          if (tags.isEmpty()) list.addView(TagListUi.empty(this));
          for (int i=0;i<tags.size();i++) {
            TagStore.Summary summary=tags.get(i);TagStore.Tag item=summary.tag;
            Button row=TagListUi.row(this,item.name,()->openTag(item));if(haveCounts)TagListUi.summary(row,item.name,summary.attached,summary.excluded);else {row.setText(item.name+"\nタグ付き — · 該当しない —");row.setContentDescription(item.name+"。写真と枚数を読み込めませんでした。タグを開く");TagListUi.photoFailure(row);}
            list.addView(row,new LinearLayout.LayoutParams(-1,-2));
            if(summary.cover!=null) worker.execute(()->{
              try{Bitmap bitmap=new PhotoRepository(this).thumbnail(summary.cover,GalleryUi.dp(this,56),()->!current(token));
                main.post(()->{if(current(token))TagListUi.photo(row,bitmap);else bitmap.recycle();});
              }catch(Exception error){main.post(()->{if(current(token)){TagListUi.photoFailure(row);row.setContentDescription(row.getContentDescription()+"。代表の写真を読み込めませんでした");}});}
            });
          }
          scroll.post(() -> { if (current(token)) scroll.scrollTo(0, tagScrollY); });
        });
      } catch (Exception error) { main.post(() -> { if (current(token)) {list.removeAllViews();list.addView(TagListUi.failure(this,this::showTags));} }); }
    });
  }

  private void createTag() {
    EditText input = new EditText(this);
    input.setSingleLine(true); input.setHint("タグ名"); input.setTextColor(GalleryUi.TEXT);
    LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.addView(input);form.addView(text("1〜80文字",12));
    StatusView inputError=new StatusView(this);form.addView(inputError);
    input.addTextChangedListener(new android.text.TextWatcher(){
      public void beforeTextChanged(CharSequence s,int start,int count,int after){}
      public void onTextChanged(CharSequence s,int start,int before,int count){}
      public void afterTextChanged(android.text.Editable value){String name=value.toString().trim();if(!name.isEmpty()&&name.length()<=80){inputError.clear();input.setStateDescription(null);input.setBackground(GalleryUi.shape(TagActivity.this,GalleryUi.PANEL,12));}}
    });
    AlertDialog dialog = new GalleryUi.Sheet(this).setTitle("新しいタグ").setView(form)
        .setPositiveButton("作成", null).setNegativeButton("キャンセル", null).create();
    dialog.setOnShowListener(ignored -> { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
      String name = input.getText().toString().trim();
      if (name.isEmpty() || name.length() > 80) { inputError.show(StatusView.Symbol.FAILED,"1〜80文字で入力してください");input.setStateDescription("入力エラー。1〜80文字で入力してください");android.graphics.drawable.GradientDrawable border=GalleryUi.shape(this,GalleryUi.PANEL,12);border.setStroke(GalleryUi.dp(this,2),0xffff8a7a);input.setBackground(border);return; }
      inputError.clear();input.setStateDescription(null);input.setBackground(GalleryUi.shape(this,GalleryUi.PANEL,12));
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
      int token = generation;
      worker.execute(() -> {
        try (TagStore store = openStore()) {
          TagStore.Tag created = store.create(name);
          main.post(() -> { if (current(token)) { dialog.dismiss(); openTag(created); } });
        } catch (Exception error) {
          main.post(() -> { if (current(token)) { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); inputError.show(StatusView.Symbol.FAILED,"タグを保存できませんでした。入力は保持されています。作成ボタンで再試行できます"); } });
        }
      });
    }); });
    dialog.show();
  }

  private void openTag(TagStore.Tag selected) {
    stopAnalysis();
    if (tag == null && tagScroll != null) tagScrollY = tagScroll.getScrollY();
    tag = selected; reviewing = false; browseMode = 0;
    int token = screen(tag.name);
    loading("写真と判断を読み込み中");
    worker.execute(() -> {
      try (TagStore store = openStore(); GalleryIndex index = new GalleryIndex(this)) {
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
        TagScores.Snapshot learned=new TagScores(store).snapshot(selected.id);
        main.post(() -> {
          if (!current(token)) return;
          photos.clear(); photos.addAll(local); photoIds.clear(); photoIds.addAll(ids); judgments = loaded; automatic = inferred; confidence=learned;displayThreshold=learned.preferences.threshold;persistedThreshold=displayThreshold;
          showTag();
        });
      } catch (Exception error) {
        main.post(() -> { if (current(token)) { showTags(); failure(error); } });
      }
    });
  }

  private Map<String,TagJudgments.Entry> latestJudgments(){
    if(indexedJudgments!=judgments||!tag.id.equals(indexedTag)){
      latestJudgments.clear();for(TagJudgments.Entry entry:judgments.history)if(entry.tagId.equals(tag.id))latestJudgments.put(entry.photoId,entry);
      indexedJudgments=judgments;indexedTag=tag.id;
    }
    return latestJudgments;
  }

  private TagJudgments.Value value(int photo) {
    String id = photoIds.get(photo);
    TagJudgments.Entry entry=id==null?null:latestJudgments().get(id);
    return entry==null?TagJudgments.Value.UNDECIDED:entry.value;
  }

  private boolean isAutomatic(int photo) {
    if(confidence!=null&&confidence.model!=null){
      String id=photoIds.get(photo);
      if(id!=null&&latestJudgments().containsKey(id))return false;
      Double score=confidence.scores.get(TagStore.source(photos.get(photo)));
      return score!=null&&score>=displayThreshold;
    }
    return value(photo) == TagJudgments.Value.UNDECIDED
        && automatic.containsKey(TagStore.source(photos.get(photo)));
  }

  private void showTag() {
    if (busy) return;
    browseMode = 0;
    stopAnalysis(); reviewing = false;
    int token = screen(tag.name);
    loading("タグを読み込み中");
    String tagId = tag.id;
    worker.execute(() -> {
      try (TagStore store = openStore()) {
        TagJudgments loaded = store.load();
        Map<String, AutomaticTags.Decision> inferred = store.automatic(tagId);
        TagScores.Snapshot learned=new TagScores(store).snapshot(tagId);
        Map<String, String> ids = store.photoIds();
        main.post(() -> {
          if (!current(token)) return;
          judgments = loaded; automatic = inferred;confidence=learned;pendingConfidence=null;displayThreshold=learned.preferences.threshold;persistedThreshold=displayThreshold;
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
    selecting=false;selectedPhotos.clear();tagOverview=true;renderPhotos(Filter.ATTACHED,0,0);
  }

  private void tagMenu(){
    new GalleryUi.Sheet(this).setTitle("その他").setItems(new String[]{"自動で付いた写真","自動で付いたタグをすべて外す"},(d,which)->{if(which==0){selecting=false;selectedPhotos.clear();tagOverview=true;renderPhotos(Filter.AUTOMATIC,0,0);}else clearAutomatic();}).show();
  }

  private void showPhotos(Filter filter) {
    if (busy) return;
    selecting = filter==Filter.ALL; selectedPhotos.clear();tagOverview=false;
    renderPhotos(filter, 0, 0);
  }

  private void renderPhotos(Filter filter, int scrollPosition, int scrollOffset) {
    renderPhotos(filter,scrollPosition,scrollOffset,scrollPosition==0);
  }

  private void renderPhotos(Filter filter,int scrollPosition,int scrollOffset,boolean headerVisible) {
    if (busy) return;
    reviewing = true; browseMode = 1; browseFilter = filter;
    if(pendingConfidence!=null){confidence=pendingConfidence;pendingConfidence=null;displayThreshold=confidence.preferences.threshold;persistedThreshold=displayThreshold;}
    filterPhotos(filter);
    String title = filter == Filter.ALL ? "写真を追加" : filter == Filter.REMOVED ? "外した写真"
        : filter == Filter.AUTOMATIC ? "自動で付いた写真" : "タグ付きの写真";
    int token = screen(tag.name + " · " + title);
    tagActions=null;selectionActions=null;
    if(tagOverview){
      root.removeAllViews();int attachedCount=0,autoCount=0,excludedCount=0;
      for(int i=0;i<photos.size();i++){if(value(i)==TagJudgments.Value.EXCLUDED)excludedCount++;else if(value(i)==TagJudgments.Value.ASSIGNED)attachedCount++;else if(isAutomatic(i)){attachedCount++;autoCount++;}}
      LinearLayout detailHeading=TagDetailUi.heading(this,scope(),tag.name,attachedCount,autoCount,excludedCount,photos.size()-attachedCount-excludedCount);confidenceSummary=(TextView)detailHeading.getChildAt(detailHeading.getChildCount()-1);root.addView(detailHeading);
      if(filter==Filter.AUTOMATIC)root.addView(button("自動で付いた写真 ×",()->{selecting=false;selectedPhotos.clear();renderPhotos(Filter.ATTACHED,0,0);}));
    }else root.addView(text(scope() + " · " + browsePhotos.size() + "枚", 14));
    selectionButton = button(selecting ? "選択を終了" : "選択", () -> {
      if (busy) return;
      selecting = !selecting; selectedPhotos.clear(); refreshSelection();
    });

    ScrollingGrid grid = new ScrollingGrid(this); photoGrid = grid;
    LinearLayout heading=new LinearLayout(this);heading.setOrientation(LinearLayout.VERTICAL);while(root.getChildCount()>0){android.view.View child=root.getChildAt(0);root.removeViewAt(0);heading.addView(child);}grid.addHeaderView(heading,null,false);
    grid.setNumColumns(4); grid.setHorizontalSpacing(GalleryUi.dp(this, 2)); grid.setVerticalSpacing(GalleryUi.dp(this, 2));
    photoAdapter = new android.widget.BaseAdapter() {
      public int getCount() { return browsePhotos.size(); }
      public Object getItem(int position) { return photos.get(browsePhotos.get(position)); }
      public long getItemId(int position) { return position; }
      public android.view.View getView(int position, android.view.View recycled, android.view.ViewGroup parent) {
        ThumbnailCell cell = recycled instanceof ThumbnailCell ? (ThumbnailCell) recycled : new ThumbnailCell(TagActivity.this);
        TagDetailUi.configureTile(cell);
        int index = browsePhotos.get(position);
        PhotoRepository.Photo photo = photos.get(index);
        boolean reload = cell.image.getTag() != photo;
        Object binding = reload ? new Object() : cell.getTag();
        if (reload) { cell.setTag(binding); cell.image.setTag(photo); cell.image.setImageDrawable(null); }
        Double rawScore=confidence==null?null:confidence.scores.get(TagStore.source(photo));
        String scoreDescription=rawScore==null?"、適合スコア未計算":String.format(java.util.Locale.ROOT,"、未校正の適合スコア %.2f",rawScore);
        String tagDescription=isAutomatic(index)?"タグあり、推定"+scoreDescription:value(index)==TagJudgments.Value.ASSIGNED?"タグあり":"タグなし";
        cell.badges.rank="";cell.badges.tagState=isAutomatic(index)?StatusView.Symbol.AUTO_TAG:tagOverview?null:value(index)==TagJudgments.Value.ASSIGNED?StatusView.Symbol.TAG:StatusView.Symbol.NO_TAG;if(reload)cell.badges.failed=false;cell.badges.invalidate();
        cell.badges.selectionMode = selecting;
        cell.badges.selected = selectedPhotos.contains(index);
        cell.setContentDescription(photo.name + " · " + tagDescription + (selecting ? (selectedPhotos.contains(index) ? " · 選択済み" : " · 未選択") : ""));
        if (reload) worker.execute(() -> {
          if (!current(token)) return;
          try {
            Bitmap bitmap = PhotoRepository.bitmap(new PhotoRepository(TagActivity.this).preview(photo), 360);
            main.post(() -> {
              if (!current(token) || cell.getTag() != binding) { bitmap.recycle(); return; }
              cell.image.setImageBitmap(bitmap);
            });
          } catch (Exception error) {
            main.post(() -> { if (current(token) && cell.getTag() == binding) { cell.badges.failed = true;cell.setContentDescription(cell.getContentDescription()+"、プレビューを読み込めませんでした"); cell.badges.invalidate(); } });
          }
        });
        return cell;
      }
    };
    grid.setCells(photoAdapter);
    grid.onCellClick((parent, view, position, id) -> {
      if (busy) return;
      if (selecting) toggleSelection(position); else showAssignedPhoto(position);
    });
    grid.onCellLongClick((parent, view, position, id) -> {
      if (!busy) { selecting = true; toggleSelection(position); }
      return true;
    });
    root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
    emptyPhotos=state(StatusView.Symbol.EMPTY,"","この範囲に該当する写真はありません");emptyPhotos.setVisibility(browsePhotos.isEmpty()?android.view.View.VISIBLE:android.view.View.GONE);root.addView(emptyPhotos);
    int controlsStart = root.getChildCount();
    if(tagOverview){
      tagActions=new LinearLayout(this);tagActions.setOrientation(LinearLayout.VERTICAL);
      int attached=0,excluded=0,assigned=0;for(int i=0;i<photos.size();i++){if(value(i)==TagJudgments.Value.EXCLUDED)excluded++;else if(value(i)==TagJudgments.Value.ASSIGNED){attached++;assigned++;}else if(isAutomatic(i))attached++;}
      LinearLayout states=new LinearLayout(this);states.setBackground(GalleryUi.shape(this,0xff1c1c1f,24));
      attachedState=TagDetailUi.state(this,"タグ付き "+attached,filter!=Filter.REMOVED,()->{selectedPhotos.clear();renderPhotos(Filter.ATTACHED,0,0);});states.addView(attachedState,new LinearLayout.LayoutParams(0,-2,1));
      states.addView(TagDetailUi.state(this,"該当しない "+excluded,filter==Filter.REMOVED,()->{selectedPhotos.clear();renderPhotos(Filter.REMOVED,0,0);}),new LinearLayout.LayoutParams(0,-2,1));((LinearLayout.LayoutParams)states.getChildAt(1).getLayoutParams()).leftMargin=GalleryUi.dp(this,4);tagActions.addView(states);tagActions.addView(confidenceControl());
      Button related=TagDetailUi.action(this,"写真を解析",true,this::startAutomatic);related.setEnabled(!photos.isEmpty()&&(!autoRunning||analysisFinished&&!autoPending));analysisAction=related;tagActions.addView(related);
      Button manual=TagDetailUi.action(this,"基準を教える",false,this::startTeaching);manual.setEnabled(!photos.isEmpty());tagActions.addView(manual);
      tagActions.addView(TagDetailUi.dock(this,TagDetailUi.dockButton(this,"写真を追加",()->showPhotos(Filter.ALL)),TagDetailUi.dockButton(this,"一括訂正",()->{selecting=true;selectedPhotos.clear();refreshSelection();}),TagDetailUi.dockButton(this,"その他",this::tagMenu)));TagDetailUi.gaps(tagActions);root.addView(tagActions);
    }
    selectionActions=new LinearLayout(this);selectionActions.setOrientation(LinearLayout.VERTICAL);
    batchButton=button("",this::changeSelected);selectionActions.addView(batchButton);
    selectionActions.addView(GalleryUi.adaptiveDock(this,selectionButton,button("すべて選択",()->{if(busy)return;selectedPhotos.addAll(browsePhotos);refreshSelection();})));
    root.addView(selectionActions);savingStatus=new StatusView(this);savingStatus.collapseWhenIdle();root.addView(savingStatus);refreshSelection();
    TagDetailUi.footer(root, controlsStart);
    refreshConfidenceCounts();grid.restoreCell(scrollPosition,scrollOffset,headerVisible);
  }

  private void filterPhotos(Filter filter){
    browsePhotos.clear();
    for(int i=0;i<photos.size();i++){
      boolean attached=isAutomatic(i)||value(i)==TagJudgments.Value.ASSIGNED;
      if(filter==Filter.ALL||filter==Filter.ATTACHED&&attached||filter==Filter.AUTOMATIC&&isAutomatic(i)||filter==Filter.REMOVED&&value(i)==TagJudgments.Value.EXCLUDED)browsePhotos.add(i);
    }
  }

  private LinearLayout confidenceControl(){
    LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(0,0,0,0);
    StatusView status=new StatusView(this);status.asCompact();learningStatus=status;box.addView(status,new LinearLayout.LayoutParams(-2,GalleryUi.dp(this,28)));
    TextView label=text("",14);label.setPadding(0,0,0,0);box.addView(label);
    android.widget.SeekBar slider=new android.widget.SeekBar(this);slider.setMax(100);slider.setKeyProgressIncrement(1);slider.setProgress((int)Math.round(displayThreshold*100));slider.setMinHeight(GalleryUi.dp(this,48));
    slider.setProgressTintList(android.content.res.ColorStateList.valueOf(GalleryUi.TEXT));slider.setThumbTintList(android.content.res.ColorStateList.valueOf(GalleryUi.TEXT));
    box.addView(slider,new LinearLayout.LayoutParams(-1,GalleryUi.dp(this,48)));
    LinearLayout ends=new LinearLayout(this);TextView broad=text("広く拾う",12),strict=text("絞り込む",12);broad.setPadding(0,0,0,0);strict.setPadding(0,0,0,0);strict.setGravity(Gravity.RIGHT);ends.addView(broad,new LinearLayout.LayoutParams(0,-2,1));ends.addView(strict,new LinearLayout.LayoutParams(0,-2,1));box.addView(ends);
    if(confidence==null||confidence.model==null){
      Map<String,TagJudgments.Entry> latest=latestJudgments();
      int yes=0,no=0;for(TagJudgments.Entry e:latest.values()){if(e.value==TagJudgments.Value.ASSIGNED)yes++;if(e.value==TagJudgments.Value.EXCLUDED)no++;}
      status.show(StatusView.Symbol.UNKNOWN,"＋"+yes+" −"+no,"含めるの判断"+yes+"枚、含めないの判断"+no+"枚。見本の特徴が未解析、または両方の見本が不足しています。写真を解析で特徴を取り出します",null,"");
    }
    else status.show(learning||confidence.stale||pendingConfidence!=null?StatusView.Symbol.WAITING:StatusView.Symbol.COMPLETE,"＋"+confidence.model.positives+" −"+confidence.model.negatives,"暫定の適合スコア。含める"+confidence.model.positives+"枚、含めない"+confidence.model.negatives+"枚"+(confidence.stale?"。前回の結果です":""),null,"");
    Runnable describe=()->{label.setText(String.format(java.util.Locale.ROOT,"表示の基準    %.2f · %d枚",displayThreshold,browsePhotos.size()));slider.setContentDescription(String.format(java.util.Locale.ROOT,"表示の基準 %.2f。%d枚。未校正の適合スコアで、正解率ではありません。自分の判断を優先します",displayThreshold,browsePhotos.size()));};describe.run();
    slider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){
      boolean dragging;
      public void onStartTrackingTouch(android.widget.SeekBar s){dragging=true;}
      public void onProgressChanged(android.widget.SeekBar s,int progress,boolean user){if(!user)return;displayThreshold=progress/100d;filterPhotos(browseFilter);refreshConfidenceCounts();if(photoAdapter!=null)photoAdapter.notifyDataSetChanged();describe.run();DiagnosticLog.event("tag.threshold.preview",0,"threshold",progress,"visible",browsePhotos.size());if(!dragging)persistThreshold(slider,describe);}
      public void onStopTrackingTouch(android.widget.SeekBar s){dragging=false;persistThreshold(slider,describe);}
    });
    if(autoRunning){
      LinearLayout progress=new LinearLayout(this);analysisStatus=new StatusView(this);analysisStatus.asCompact();assignmentStatus=new StatusView(this);assignmentStatus.asCompact();failureCount=new StatusView(this);failureCount.asCompact();failureCount.collapseWhenIdle();
      progress.addView(analysisStatus);progress.addView(assignmentStatus);progress.addView(failureCount);box.addView(progress);
      resultButton=button("中止",this::loadResults);box.addView(resultButton);updateProgress();
    }
    return box;
  }

  private void refreshConfidenceCounts(){
    int attached=0,inferred=0,excluded=0;
    for(int i=0;i<photos.size();i++){if(value(i)==TagJudgments.Value.EXCLUDED)excluded++;else if(value(i)==TagJudgments.Value.ASSIGNED)attached++;else if(isAutomatic(i)){attached++;inferred++;}}
    if(confidenceSummary!=null)confidenceSummary.setText("タグ付き "+attached+"枚"+(inferred>0?"（推定 "+inferred+"枚）":"")+" · 該当しない "+excluded+"枚 · 未確認 "+(photos.size()-attached-excluded)+"枚");
    if(attachedState!=null){attachedState.setText((browseFilter==Filter.REMOVED?"   ":"✓  ")+"タグ付き "+attached);attachedState.setContentDescription("タグ付き "+attached);}
    if(emptyPhotos!=null)emptyPhotos.setVisibility(browsePhotos.isEmpty()?android.view.View.VISIBLE:android.view.View.GONE);
  }

  private void persistThreshold(android.widget.SeekBar slider,Runnable describe){
    if(busy)return;busy=true;slider.setEnabled(false);int token=generation;String tagId=tag.id;double target=displayThreshold;
    worker.execute(()->{try(TagStore store=openStore()){
      TagScores scores=new TagScores(store);scores.threshold(tagId,target);
      main.post(()->{busy=false;if(current(token)){persistedThreshold=target;slider.setEnabled(true);}});
    }catch(Exception error){main.post(()->{busy=false;if(current(token)){displayThreshold=persistedThreshold;slider.setProgress((int)Math.round(displayThreshold*100));filterPhotos(browseFilter);refreshConfidenceCounts();photoAdapter.notifyDataSetChanged();describe.run();slider.setEnabled(true);StatusFeedback.show(this,StatusView.Symbol.FAILED,"表示の基準を保存できませんでした。もう一度操作してください",null,"");}});}});
  }

  /** Saving a judgment never extracts a new image. Results wait until the next list render. */
  private void relearnCached(){
    int epoch=++learningGeneration;String tagId=tag.id;learning=true;
    if(learningStatus!=null)learningStatus.show(StatusView.Symbol.WAITING,"保存した判断から計算しています。前回の結果を表示しています");
    worker.execute(()->{try(TagStore store=openStore();PhotoFeatures cached=new PhotoFeatures(this,featureDatabaseName)){
      TagScores scores=new TagScores(store);if(!scores.preferences(tagId).enabled){main.post(()->{if(epoch==learningGeneration)learning=false;});return;}
      scores.recompute(tagId,cached,()->isDestroyed()||epoch!=learningGeneration);
      TagScores.Snapshot saved=scores.snapshot(tagId);
      main.post(()->{if(!isDestroyed()&&epoch==learningGeneration&&tag!=null&&tag.id.equals(tagId)){pendingConfidence=saved;learning=false;if(learningStatus!=null)learningStatus.show(StatusView.Symbol.WAITING,"新しい結果を用意しました。一覧を表示し直すと反映します");}});
    }catch(java.util.concurrent.CancellationException cancelled){}
    catch(Exception error){main.post(()->{if(!isDestroyed()&&epoch==learningGeneration){learning=false;StatusFeedback.show(this,StatusView.Symbol.FAILED,"判断は保存しましたが、スコアを更新できませんでした。写真を解析から再試行できます",null,"");}});}});
  }

  private void startTeaching(){
    if(busy)return;stopAnalysis();teachingSeen.clear();teachingUndo.clear();teachingCount=0;teachingTotal=0;for(TagJudgments.Value value:labels())if(value==TagJudgments.Value.UNDECIDED)teachingTotal++;nextTeaching();
  }

  private void nextTeaching(){
    Map<Integer,Double> scores=new HashMap<>();
    if(confidence!=null)for(int i=0;i<photos.size();i++){Double score=confidence.scores.get(TagStore.source(photos.get(i)));if(score!=null)scores.put(i,score);}
    teachingIndex=TagConfidence.next(labels(),scores,teachingSeen,displayThreshold,teachingCount);
    showTeaching();
  }

  private void showTeaching(){
    browseMode=3;reviewing=true;int token=screen(tag.name);root.removeAllViews();root.setBackgroundColor(android.graphics.Color.BLACK);teachingButtons.clear();
    LinearLayout heading=new LinearLayout(this);heading.setOrientation(LinearLayout.VERTICAL);heading.setPadding(GalleryUi.dp(this,20),GalleryUi.dp(this,40),GalleryUi.dp(this,20),GalleryUi.dp(this,12));
    TextView name=text(tag.name,17);name.setPadding(0,0,0,0);name.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);heading.addView(name);TextView context=text(scope()+" · "+Math.min(teachingCount+1,teachingTotal)+" / "+teachingTotal,13);context.setPadding(0,0,0,0);heading.addView(context);root.addView(heading);
    ZoomImageView image=new ZoomImageView(this);root.addView(image,new LinearLayout.LayoutParams(-1,0,1));
    teachingStatus=new StatusView(this);teachingStatus.collapseWhenIdle();root.addView(teachingStatus);
    if(teachingIndex<0){image.setContentDescription("未判断の写真はありません");teachingStatus.show(StatusView.Symbol.COMPLETE,"未判断の写真はありません");}
    else{
      PhotoRepository.Photo photo=photos.get(teachingIndex);image.setContentDescription("写真。「"+tag.name+"」が写っているか判断してください。適合スコアは未校正で、正解率ではありません");
      worker.execute(()->{try{Bitmap bitmap=PhotoRepository.bitmap(new PhotoRepository(this).preview(photo),1600);main.post(()->{if(current(token))image.setBitmap(bitmap);else bitmap.recycle();});}catch(Exception error){main.post(()->{if(current(token))teachingStatus.show(StatusView.Symbol.FAILED,"写真を読み込めませんでした。保留で次へ進めます");});}});
    }
    int controls=root.getChildCount();
    LinearLayout actions=new LinearLayout(this);actions.setOrientation(getResources().getConfiguration().screenWidthDp<360||getResources().getConfiguration().fontScale>1.15?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
    String[] titles={"含めない","保留","含める"};
    for(int i=0;i<3;i++){final int choice=i;Button b=TagDetailUi.action(this,titles[i],i==2,()->{if(choice==1){teachingSeen.add(teachingIndex);teachingCount++;nextTeaching();}else saveTeaching(choice==2?TagJudgments.Value.ASSIGNED:TagJudgments.Value.EXCLUDED,false);});b.setContentDescription(titles[i]+"。"+(i==1?"判断せず次へ。学習には使いません":"この写真を「"+tag.name+"」に"+titles[i]));b.setEnabled(teachingIndex>=0);teachingButtons.add(b);actions.addView(b,new LinearLayout.LayoutParams(actions.getOrientation()==LinearLayout.HORIZONTAL?0:-1,-2,actions.getOrientation()==LinearLayout.HORIZONTAL?1:0));}
    root.addView(actions);Button undo=TagDetailUi.action(this,"取り消し",false,()->saveTeaching(null,true));undo.setBackgroundColor(android.graphics.Color.TRANSPARENT);undo.setTextSize(12);undo.setContentDescription("直前の判断を取り消す");undo.setEnabled(!teachingUndo.isEmpty());teachingButtons.add(undo);root.addView(undo);TagDetailUi.footer(root,controls);
    if(actions.getOrientation()==LinearLayout.VERTICAL)TagDetailUi.gaps(actions);else for(int i=1;i<actions.getChildCount();i++)((LinearLayout.LayoutParams)actions.getChildAt(i).getLayoutParams()).leftMargin=GalleryUi.dp(this,8);
  }

  private void saveTeaching(TagJudgments.Value choice,boolean undo){
    if(busy||undo&&teachingUndo.isEmpty()||!undo&&teachingIndex<0)return;
    busy=true;teachingChoice=undo?3:choice==TagJudgments.Value.ASSIGNED?2:0;for(Button b:teachingButtons)b.setEnabled(false);teachingButtons.get(teachingChoice).setTag(StatusView.Symbol.RUNNING);teachingButtons.get(teachingChoice).setStateDescription("保存中");teachingButtons.get(teachingChoice).invalidate();teachingStatus.clear();int token=generation,index=teachingIndex;String tagId=tag.id;
    String target=undo?teachingUndo.get(teachingUndo.size()-1):null,operation=UUID.randomUUID().toString();
    String id=index<0?null:photoIds.get(index);TagJudgments.Entry entry=id==null?null:judgments.latest(tagId,id);long revision=entry==null?0:entry.revision;
    worker.execute(()->{try(TagStore store=openStore()){
      String savedId=undo?null:store.photoId(photos.get(index),true);
      TagJudgments saved=undo?store.undo(operation,target):store.record(operation,tagId,savedId,revision,choice);
      main.post(()->{busy=false;if(!current(token))return;judgments=saved;relearnCached();
        if(undo){TagJudgments.Entry undone=saved.history.get(saved.history.size()-1);teachingUndo.remove(teachingUndo.size()-1);for(int i=0;i<photoIds.size();i++)if(undone.photoId.equals(photoIds.get(i))){teachingIndex=i;teachingSeen.remove(i);break;}teachingCount=Math.max(0,teachingCount-1);showTeaching();}
        else{photoIds.set(index,savedId);teachingUndo.add(operation);teachingSeen.add(index);teachingCount++;nextTeaching();}
      });
    }catch(Exception error){main.post(()->{busy=false;if(current(token)){for(int i=0;i<teachingButtons.size();i++)teachingButtons.get(i).setEnabled(i==3?!teachingUndo.isEmpty():teachingIndex>=0);Button failed=teachingButtons.get(teachingChoice);failed.setTag(StatusView.Symbol.FAILED);failed.invalidate();failed.setStateDescription("保存できませんでした。写真と判断はそのままです。もう一度押して再試行できます");failed.announceForAccessibility(failed.getStateDescription());}});}});
  }

  private void toggleSelection(int position) {
    int index = browsePhotos.get(position);
    if (!selectedPhotos.add(index)) selectedPhotos.remove(index);
    refreshSelection();
  }

  private void refreshSelection() {
    selectionButton.setText(selecting ? "選択を終了" : "選択");
    if(tagActions!=null)tagActions.setVisibility(selecting?android.view.View.GONE:android.view.View.VISIBLE);
    if(selectionActions!=null)selectionActions.setVisibility(tagOverview&&!selecting?android.view.View.GONE:android.view.View.VISIBLE);
    batchButton.setVisibility(selecting ? android.view.View.VISIBLE : android.view.View.GONE);
    boolean remove = browseFilter == Filter.ATTACHED || browseFilter == Filter.AUTOMATIC;
    batchButton.setText(remove ? "該当しないにする" : "タグ付きにする");
    batchButton.setContentDescription(selectedPhotos.size()+"枚を"+batchButton.getText());
    batchButton.setEnabled(!busy && !selectedPhotos.isEmpty()); selectionButton.setEnabled(!busy);
    if(savingStatus!=null){if(busy)savingStatus.begin("タグを保存しています");else savingStatus.finish();}
    photoAdapter.notifyDataSetChanged();
  }

  private void changeSelected() {
    if (busy || selectedPhotos.isEmpty()) return;
    List<Integer> selected = new ArrayList<>(selectedPhotos);
    int first = photoGrid.firstCellPosition();
    boolean headerVisible=photoGrid.getFirstVisiblePosition()<photoGrid.getHeaderViewsCount();
    int anchor = browsePhotos.get(Math.min(first, browsePhotos.size() - 1));
    int offset = photoGrid.getChildCount() == 0 ? 0 : photoGrid.getChildAt(0).getTop();
    String tagId = tag.id, operation = UUID.randomUUID().toString();
    TagJudgments baseline = judgments;
    TagJudgments.Value value = browseFilter == Filter.ATTACHED || browseFilter == Filter.AUTOMATIC
        ? TagJudgments.Value.EXCLUDED : TagJudgments.Value.ASSIGNED;
    int token = generation; busy = true; refreshSelection();
    worker.execute(() -> {
      try (TagStore store = openStore()) {
        Map<String, Long> expected = new java.util.LinkedHashMap<>();
        Map<Integer, String> ids = new HashMap<>();
        for (int index : selected) {
          String id = store.photoId(photos.get(index), true); ids.put(index, id);
          TagJudgments.Entry entry = baseline.latest(tagId, id);
          expected.put(id, entry == null ? 0L : entry.revision);
        }
        TagJudgments saved = store.recordBatch(operation, tagId, expected, value);
        Map<String, AutomaticTags.Decision> inferred = store.automatic(tagId);
        TagScores.Snapshot learned=new TagScores(store).snapshot(tagId);
        main.post(() -> {
          busy = false; if (!current(token)) return;
          judgments = saved; automatic = inferred;
          relearnCached();
          for (Map.Entry<Integer, String> entry : ids.entrySet()) photoIds.set(entry.getKey(), entry.getValue());
          selectedPhotos.clear();selecting=false;
          int target = 0;
          for (int index : browsePhotos) {
            if (index >= anchor) break;
            if (value != TagJudgments.Value.EXCLUDED || !selected.contains(index)) target++;
          }
          renderPhotos(browseFilter, target, offset, headerVisible);
          StatusFeedback.show(this,StatusView.Symbol.COMPLETE,selected.size()+(value==TagJudgments.Value.EXCLUDED?"枚のタグを外しました":"枚にタグを付けました"),null,"");
        });
      } catch (Exception error) {
        main.post(() -> { busy = false; if (current(token)) { refreshSelection(); StatusFeedback.show(this,StatusView.Symbol.FAILED,"タグを保存できませんでした。選択は保持されています。",this::changeSelected,"再試行"); } });
      }
    });
  }

  private void showAssignedPhoto(int position) {
    if (busy) return;
    if (browseMode == 1 && photoGrid != null) {
      photoGridState = photoGrid.onSaveInstanceState();
    }
    browseMode = 2;
    int index = browsePhotos.get(position);
    PhotoRepository.Photo photo = photos.get(index);
    int token = screen(tag.name);
    root.removeAllViews();root.setBackgroundColor(android.graphics.Color.BLACK);
    PhotoViewerUi viewer = new PhotoViewerUi(this,photo);
    photoViewer=viewer;photoPosition=position;
    root.addView(viewer,new LinearLayout.LayoutParams(-1,0,1));
    photoAttached=isAutomatic(index)||value(index)==TagJudgments.Value.ASSIGNED;
    viewer.image.setContentDescription(viewer.image.getContentDescription()+"、タグ「"+tag.name+"」、"+(photoAttached?(isAutomatic(index)?"自動で付けました":"自分で付けました"):"タグなし"));
    Runnable previous=()->{if(position>0)showAssignedPhoto(position-1);};
    Runnable next=()->{if(position+1<browsePhotos.size())showAssignedPhoto(position+1);};
    viewer.image.setPageListener(direction->{if(direction>0)next.run();else previous.run();});
    photoPrevious=viewer.action("前の写真","前へ","前の写真",previous);
    photoNext=viewer.action("次の写真","次へ","次の写真",next);
    viewer.zoom();
    photoTag=viewer.action("タグ",photoAttached?"タグを外す":"タグを付ける",photoAttached?"タグを外す":"タグを付ける",
        ()->changeTag(index,position,photoAttached?TagJudgments.Value.EXCLUDED:TagJudgments.Value.ASSIGNED));
    photoTag.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
    refreshPhotoSave(false);
    StatusView loading=new StatusView(this);loading.asPhotoOverlay();viewer.status(loading);loading.begin("写真を読み込んでいます");
    worker.execute(()->{
      try{
        Bitmap bitmap=PhotoRepository.bitmap(new PhotoRepository(this).preview(photo),1600);
        main.post(()->{if(current(token)){viewer.image.setBitmap(bitmap);loading.finish();viewer.refreshStatus();}else bitmap.recycle();});
      }catch(Exception error){main.post(()->{if(current(token)){
        viewer.failed();viewer.stage.removeView(loading);
        LinearLayout recovery=new LinearLayout(this);recovery.setOrientation(LinearLayout.VERTICAL);recovery.setGravity(Gravity.CENTER);
        loading.asPhotoFailure();loading.show(StatusView.Symbol.FAILED,"写真を読み込めませんでした");recovery.addView(loading,new LinearLayout.LayoutParams(GalleryUi.dp(this,32),GalleryUi.dp(this,32)));
        Button retry=button("再試行",()->showAssignedPhoto(position));retry.setContentDescription("写真を読み込めませんでした。再試行");retry.setTextSize(14);retry.setPadding(GalleryUi.dp(this,18),0,GalleryUi.dp(this,18),0);
        android.graphics.drawable.GradientDrawable background=GalleryUi.shape(this,android.graphics.Color.BLACK,24);background.setStroke(GalleryUi.dp(this,1),0xff3a3a3f);retry.setBackground(background);
        ActionIcon icon=new ActionIcon("再試行",getResources().getDisplayMetrics().density*20/26);icon.setBounds(0,0,GalleryUi.dp(this,20),GalleryUi.dp(this,20));retry.setCompoundDrawables(icon,null,null,null);retry.setCompoundDrawablePadding(GalleryUi.dp(this,6));
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-2,GalleryUi.dp(this,48));rp.topMargin=GalleryUi.dp(this,12);recovery.addView(retry,rp);
        viewer.stage.addView(recovery,new android.widget.FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
      }});}
    });
  }

  private void refreshPhotoSave(boolean failed){
    if(photoViewer==null||browseMode!=2)return;
    photoPrevious.setEnabled(!busy&&photoPosition>0);photoNext.setEnabled(!busy&&photoPosition+1<browsePhotos.size());photoTag.setEnabled(!busy);
    String label=photoAttached?"タグを外す":"タグを付ける";
    photoTag.setContentDescription(label+"、タグ「"+tag.name+"」"+(busy?"。保存しています":failed?"。保存できませんでした。判断はそのままです。もう一度押すと保存します":""));
    photoTag.setStateDescription(busy?"保存中":failed?"保存失敗、再試行できます":photoAttached?"タグあり":"タグなし");
    PhotoViewerUi.tagState(photoTag,photoAttached,busy,failed);
  }

  private void clearAutomatic() {
    new GalleryUi.Sheet(this).setTitle("自動で付いたタグをすべて外しますか？")
        .setMessage("「" + tag.name + "」が対象です。全アルバムの自動で付いた分を外します。自分で付けたタグは残ります。")
        .setPositiveButton("すべて外す", (d, w) -> {
          stopAnalysis();learningGeneration++; busy = true;
          String tagId = tag.id;
          worker.execute(() -> {
            try (TagStore store = openStore()) {
              store.automation(tagId, false, true);
              main.post(() -> { busy = false; if (!isDestroyed()) showTag(); });
            } catch (Exception error) { main.post(() -> { busy = false; failure(error); }); }
          });
        }).setNegativeButton("外さない", null).show();
  }

  static final class PhotoTagSave {
    final String photoId;final TagJudgments judgments;final boolean conflict;
    PhotoTagSave(String photoId,TagJudgments judgments,boolean conflict){this.photoId=photoId;this.judgments=judgments;this.conflict=conflict;}
  }
  static PhotoTagSave savePhotoTag(TagStore store,PhotoRepository.Photo photo,String operation,String tagId,long revision,TagJudgments.Value value){
    String id=store.photoId(photo,true);
    try{return new PhotoTagSave(id,store.record(operation,tagId,id,revision,value),false);}
    catch(IllegalStateException error){
      TagJudgments latest=store.load();TagJudgments.Entry current=latest.latest(tagId,id);
      if((current==null?0:current.revision)==revision)throw error;
      return new PhotoTagSave(id,latest,true);
    }
  }

  private void changeTag(int index, int position, TagJudgments.Value value) {
    if (busy) return;
    busy = true;
    refreshPhotoSave(false);
    int token = generation;
    String tagId = tag.id, id = photoIds.get(index);
    TagJudgments.Entry entry = id == null ? null : judgments.latest(tagId, id);
    long revision = entry == null ? 0 : entry.revision;
    worker.execute(() -> {
      try (TagStore store = openStore()) {
        PhotoTagSave saved=savePhotoTag(store,photos.get(index),UUID.randomUUID().toString(),tagId,revision,value);
        main.post(() -> {
          busy = false; if (!current(token)) return;
          photoIds.set(index,saved.photoId);judgments=saved.judgments;
          if(!saved.conflict)relearnCached();
          showAssignedPhoto(position);
          if(saved.conflict){
            refreshPhotoSave(true);
            String message="ほかの操作でタグが変更されました。最新の判断を表示しています。内容を確認してもう一度操作してください";
            photoTag.setContentDescription(photoTag.getText()+"、タグ「"+tag.name+"」。"+message);photoTag.setStateDescription("変更競合、最新の判断を表示しています");photoTag.announceForAccessibility(message);
          }
        });
      } catch (Exception error) { main.post(() -> { busy = false; if (current(token)) refreshPhotoSave(true); }); }
    });
  }

  private void stopAnalysis() {
    if (autoRunning && tag != null) {
      String tagId = tag.id;
      autoRunning = false;
      worker.execute(() -> {
        try (TagStore store = openStore()) { store.automation(tagId, false, false); }
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
    boolean done=analysisFinished&&!autoPending&&!autoAgain&&automaticError.isEmpty();
    if(analysisStatus!=null){
      StatusView.Symbol kind=!automaticError.isEmpty()?StatusView.Symbol.FAILED:done?(analysisFailures>0?StatusView.Symbol.PARTIAL:StatusView.Symbol.COMPLETE):analysisPaused?StatusView.Symbol.PAUSED:StatusView.Symbol.RUNNING;
      analysisStatus.observe(kind,analyzed+"/"+photos.size(),progress());
      assignmentStatus.observe(autoPending?StatusView.Symbol.RUNNING:StatusView.Symbol.AUTO_TAG,Integer.toString(automaticCount),"自動付与"+automaticCount+"枚"+(autoPending?"、保存中":""));
      if(analysisFailures>0)failureCount.show(StatusView.Symbol.FAILED,Integer.toString(analysisFailures),"取得失敗"+analysisFailures+"枚",null,"");else failureCount.clear();
    }
    if (resultButton != null) resultButton.setText(done ? "結果を見る" : "中止");
    if(analysisAction!=null)analysisAction.setEnabled(!busy&&(!autoRunning||done));
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
      try (TagStore store = openStore()) {
        long epoch = new TagScores(store).enable(tagId);
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

  private void showAutomaticProgress(){tagOverview=true;selecting=false;selectedPhotos.clear();renderPhotos(Filter.ATTACHED,0,0);}

  private void loadResults() {
    if (busy) return;
    stopAnalysis(); busy = true;
    String tagId = tag.id;
    int token = generation;
    worker.execute(() -> {
      try (TagStore store = openStore()) {
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
    String tagId = tag.id;
    worker.execute(() -> {
      try (TagStore store = openStore();PhotoFeatures cached=new PhotoFeatures(this,featureDatabaseName)) {
        TagScores scores=new TagScores(store);
        boolean applied=scores.recompute(tagId,cached,()->token!=analysisGeneration||isDestroyed());
        Map<String,AutomaticTags.Decision> saved=store.automatic(tagId);
        TagScores.Snapshot learned=scores.snapshot(tagId);Map<String,String> ids=store.photoIds();
        main.post(()->{
          if(isDestroyed()||token!=analysisGeneration||!autoRunning)return;
          autoPending=false;pendingConfidence=learned;
          for(int i=0;i<photos.size();i++)photoIds.set(i,ids.get(TagStore.source(photos.get(i))));
          updateAutomaticCount();automaticError="";updateProgress();
          if(!applied&&learned.model==null&&analysisFinished)assignmentStatus.show(StatusView.Symbol.EMPTY,"含める・含めないの特徴がそれぞれ1枚以上必要です");
          if(autoAgain)requestAutomatic();
        });
      }catch(java.util.concurrent.CancellationException cancelled){/* Stopped jobs retain the last complete result. */}
      catch(Exception error){main.post(()->{if(!isDestroyed()&&token==analysisGeneration){autoPending=false;automaticError="スコアを保存できませんでした。前回の結果は保持しています。";updateProgress();}});}
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
      try (PhotoFeatures extractor = new PhotoFeatures(this,featureDatabaseName)) {
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
    else if (browseMode == 3) {showTag();}
    else if (browseMode == 2) {
      renderPhotos(browseFilter, 0, 0);
      if (photoGridState != null) photoGrid.onRestoreInstanceState(photoGridState);
    }
    else if (browseMode==1 && tagOverview) showTags();
    else if (reviewing) showTag();
    else if (tag != null) showTags();
    else super.onBackPressed();
  }

  protected void onDestroy() {
    learningGeneration++;stopAnalysis(); generation++; worker.shutdown(); analysis.shutdownNow(); super.onDestroy();
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
