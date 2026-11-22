package jp.nano.rawgallery;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.CheckedTextView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/** Read-only locations and move eligibility for files referenced by import history. */
public final class PhotoStorageInventoryActivity extends AppCompatActivity {
  private record Filter(String key, String label) {}
  private record VolumeCount(String label, int confirmed) {}

  private static final Filter ALL = new Filter("all", "すべて");
  private static final Filter UNRESOLVED = new Filter("history", "確認が必要");
  private static final Filter EXCLUDED = new Filter("excluded", "移動対象外");
  private static final String VOLUME_PREFIX = "volume:";
  // Serialize settings writes and both screens' reads, including when one opens while the other is loading.
  static final ExecutorService READS = new DiagnosticExecutor("PhotoStorage.inventory", 1);

  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable checkMove = this::checkMove;
  private final StorageManager.StorageVolumeCallback volumeChanges = new StorageManager.StorageVolumeCallback() {
    @Override public void onStateChanged(StorageVolume volume) { if (visible) load(); }
  };

  private TextView state;
  private MaterialButton filterButton, reloadButton;
  private ListView list;
  private InventoryRows rows;
  private AlertDialog openDialog;
  private Future<?> pendingLoad;
  private PhotoMoveService.State observedMove;
  private PhotoStorageInventory snapshot;
  private String filterKey = ALL.key();
  private boolean visible;
  private int loadRequest;

  @Override public void onCreate(Bundle saved) {
    super.onCreate(saved);
    if (saved != null) filterKey = saved.getString("filter", ALL.key());
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setOnApplyWindowInsetsListener((view, insets) -> {
      var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
      view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
      return insets;
    });
    root.addView(LibraryUi.toolbar(this, "写真の保存場所",
        LibraryUi.icon(this, R.drawable.ic_close, "写真の保存場所を閉じる", this::finish)));

    LinearLayout actions = new LinearLayout(this);
    actions.setPadding(dp(16), dp(4), dp(16), 0);
    filterButton = LibraryUi.text(this, "絞り込み", this::chooseFilter);
    MaterialUi.outlined(filterButton);
    reloadButton = LibraryUi.text(this, "再読み込み", this::load);
    actions.addView(filterButton, new LinearLayout.LayoutParams(0, -2, 1));
    actions.addView(reloadButton, new LinearLayout.LayoutParams(0, -2, 1));
    root.addView(actions, new LinearLayout.LayoutParams(-1, -2));

    state = text(14, GalleryUi.MUTED);
    state.setPadding(dp(20), dp(12), dp(20), dp(12));
    state.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    root.addView(state, new LinearLayout.LayoutParams(-1, -2));

    rows = new InventoryRows();
    list = new ListView(this);
    list.setAdapter(rows);
    list.setOnItemClickListener((parent, row, position, id) -> showDetail(rows.getItem(position)));
    root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
    setContentView(root);
  }

  @Override public void onResume() {
    super.onResume();
    visible = true;
    getSystemService(StorageManager.class).registerStorageVolumeCallback(getMainExecutor(), volumeChanges);
    load();
    checkMove();
  }

  @Override public void onPause() {
    visible = false;
    ++loadRequest;
    getSystemService(StorageManager.class).unregisterStorageVolumeCallback(volumeChanges);
    handler.removeCallbacks(checkMove);
    if (pendingLoad != null) pendingLoad.cancel(false);
    super.onPause();
  }

  @Override protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putString("filter", filterKey);
  }

  @Override public void onDestroy() {
    if (openDialog != null) openDialog.dismiss();
    super.onDestroy();
  }

  private void checkMove() {
    PhotoMoveService.State current = PhotoMoveService.state();
    if (current != observedMove && (!current.running() || !observedMove.running())) load();
    if (visible) handler.postDelayed(checkMove, 500);
  }

  private void load() {
    if (!visible || isFinishing() || isDestroyed()) return;
    if (openDialog != null) openDialog.dismiss();
    if (pendingLoad != null) pendingLoad.cancel(false);
    int request = ++loadRequest;
    PhotoMoveService.State moveState = PhotoMoveService.state();
    observedMove = moveState;
    snapshot = null;
    rows.replace(List.of(), List.of());
    filterButton.setEnabled(false);
    if (moveState.running()) {
      state.setText("写真を移動しています。保存先画面で進捗を確認できます。");
      reloadButton.setEnabled(false);
      return;
    }
    state.setText("保存場所を確認しています。");
    reloadButton.setEnabled(false);
    pendingLoad = READS.submit(() -> {
      try (PhotoMove mover = new PhotoMove(this)) {
        PhotoStorageInventory result = mover.inventory();
        List<PhotoStorageInventory.Photo> photos = result.photos().stream()
            .sorted(Comparator.comparing(PhotoStorageInventory.Photo::name, String.CASE_INSENSITIVE_ORDER))
            .collect(Collectors.toList());
        List<PhotoStorageInventory.History> history = result.unresolved().stream()
            .sorted(Comparator.comparing(PhotoStorageInventory.History::name, String.CASE_INSENSITIVE_ORDER))
            .collect(Collectors.toList());
        finishLoad(request, moveState, new PhotoStorageInventory(photos, history), null);
      } catch (PhotoMove.Failure | PhotoStorage.Unavailable unavailable) {
        finishLoad(request, moveState, null, unavailable.getMessage());
      } catch (IOException | RuntimeException unavailable) {
        finishLoad(request, moveState, null, "保存場所を読み込めませんでした。もう一度お試しください。");
      }
    });
  }

  private void finishLoad(int request, PhotoMoveService.State moveState, PhotoStorageInventory result, String message) {
    runOnUiThread(() -> {
      if (request != loadRequest || !visible || isFinishing() || isDestroyed()
          || PhotoMoveService.state() != moveState) return;
      reloadButton.setEnabled(true);
      snapshot = result;
      if (result == null) {
        state.setText(message);
      } else {
        filterButton.setEnabled(true);
        showRows();
      }
    });
  }

  private List<Filter> filters() {
    List<Filter> filters = new ArrayList<>();
    filters.add(ALL);
    volumeCounts(snapshot).forEach((volume, count) -> {
      if (volume != null) filters.add(new Filter(VOLUME_PREFIX + volume, count.label()));
    });
    filters.add(UNRESOLVED);
    filters.add(EXCLUDED);
    return filters;
  }

  private void chooseFilter() {
    if (snapshot == null) return;
    List<Filter> choices = filters();
    List<String> labels = choices.stream().map(Filter::label).collect(Collectors.toList());
    ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_single_choice, labels) {
      @Override public View getView(int position, View convertView, ViewGroup parent) {
        CheckedTextView view = (CheckedTextView) super.getView(position, convertView, parent);
        view.setSingleLine(false);
        view.setEllipsize(null);
        view.setMinHeight(dp(48));
        return view;
      }
    };
    int checked = choices.indexOf(activeFilter(choices));
    openDialog = new MaterialAlertDialogBuilder(this).setTitle("表示する項目")
        .setSingleChoiceItems(adapter, checked, (dialog, which) -> {
          filterKey = choices.get(which).key();
          dialog.dismiss();
          showRows();
        })
        .setNegativeButton("キャンセル", null).show();
  }

  private Filter activeFilter(List<Filter> choices) {
    return choices.stream().filter(choice -> choice.key().equals(filterKey)).findFirst().orElse(ALL);
  }

  private void showRows() {
    Filter filter = activeFilter(filters());
    filterKey = filter.key();
    List<PhotoStorageInventory.Photo> photos = snapshot.photos();
    List<PhotoStorageInventory.History> history = snapshot.unresolved();
    if (filter.equals(UNRESOLVED)) photos = photos.stream()
        .filter(photo -> !photo.historyMatches() || photo.location().kind() != PhotoStorageLocation.Kind.CURRENT)
        .collect(Collectors.toList());
    else if (filter.equals(EXCLUDED)) {
      photos = photos.stream().filter(photo -> photo.status() == PhotoStorageInventory.Status.EXCLUDED)
          .collect(Collectors.toList());
      history = List.of();
    }
    else if (!filter.equals(ALL)) {
      String volume = filter.key().substring(VOLUME_PREFIX.length());
      photos = photos.stream().filter(photo -> volume.equals(photo.location().volume())).collect(Collectors.toList());
      history = List.of();
    }
    rows.replace(photos, history);
    list.setSelection(0);
    String selection = filter.equals(ALL) ? "" : filter.label() + "\n";
    filterButton.setStateDescription(filter.label());
    long confirmed = photos.stream().filter(PhotoStorageInventory.Photo::historyMatches).count();
    String counts = "履歴と一致した写真 " + confirmed + "枚" + candidateCounts(photos)
        + "\n確認が必要な履歴 " + historyCount(history) + "件";
    state.setText(selection + counts + (rows.isEmpty() ? "\n表示する項目はありません。" : ""));
  }

  static String summary(PhotoStorageInventory inventory) {
    Map<String, VolumeCount> counts = volumeCounts(inventory);
    int confirmed = counts.values().stream().mapToInt(VolumeCount::confirmed).sum();
    var lines = new StringBuilder("履歴と一致した写真 ").append(confirmed).append("枚");
    counts.forEach((volume, count) -> {
      if (count.confirmed() > 0)
        lines.append('\n').append(count.label()).append(' ').append(count.confirmed()).append("枚");
    });
    return lines.append(candidateCounts(inventory.photos()))
        .append("\n確認が必要な履歴 ").append(historyCount(inventory.unresolved())).append("件").toString();
  }

  private static String candidateCounts(List<PhotoStorageInventory.Photo> photos) {
    int pendingContent = 0, otherReferences = 0;
    for (var photo : photos) {
      if (photo.reasons().contains(PhotoStorageInventory.Reason.ARCHIVE_CHECK_REQUIRED)) pendingContent++;
      else if (!photo.historyMatches()) otherReferences++;
    }
    String pending = pendingContent == 0 ? "" : "\n内容の確認待ち " + pendingContent + "枚";
    String references = otherReferences == 0 ? "" : "\n参照先の写真 " + otherReferences + "枚";
    return pending + references;
  }

  private static Map<String, VolumeCount> volumeCounts(PhotoStorageInventory inventory) {
    Map<String, VolumeCount> counts = new LinkedHashMap<>();
    for (var photo : inventory.photos()) {
      PhotoStorageLocation location = photo.location();
      counts.merge(location.volume(), new VolumeCount(location.label(), photo.historyMatches() ? 1 : 0),
          (left, right) -> new VolumeCount(left.label(), left.confirmed() + right.confirmed()));
    }
    Map<String, VolumeCount> ordered = new LinkedHashMap<>();
    if (counts.containsKey(PhotoStorage.PRIMARY)) ordered.put(PhotoStorage.PRIMARY, counts.remove(PhotoStorage.PRIMARY));
    counts.entrySet().stream().sorted(Comparator.comparing(entry -> entry.getValue().label()))
        .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
    return ordered;
  }

  private static int historyCount(List<PhotoStorageInventory.History> history) {
    return history.stream().mapToInt(PhotoStorageInventory.History::count).sum();
  }

  private void showDetail(Object item) {
    if (snapshot == null) return;
    String title = "取り込み履歴の詳細";
    if (item instanceof PhotoStorageInventory.Photo photo)
      title = photo.historyMatches() ? "写真の詳細" : "参照先の写真の詳細";
    TextView details = text(16, GalleryUi.TEXT);
    details.setPadding(dp(24), dp(8), dp(24), dp(20));
    details.setText(details(item));
    ScrollView scroll = new ScrollView(this);
    scroll.addView(details, new ScrollView.LayoutParams(-1, -2));
    openDialog = new MaterialAlertDialogBuilder(this)
        .setTitle(title)
        .setView(scroll).setPositiveButton("閉じる", null).show();
  }

  private static String details(Object item) {
    if (item instanceof PhotoStorageInventory.Photo photo) {
      StringBuilder detail = new StringBuilder(photo.historyMatches() ? "写真名　" + photo.name()
          : referencePhoto(photo.name())).append('\n')
          .append(locationText(photo.location())).append("\n\n").append(moveStatus(photo));
      if (!photo.historyMatches()) detail.append("\n履歴と同じ写真か確認できません。");
      if (photo.recordedName() != null && !photo.recordedName().isEmpty())
        detail.append("\n履歴の名前　").append(photo.recordedName());
      appendReasons(detail, photo.reasons());
      return detail.toString();
    }
    var history = (PhotoStorageInventory.History) item;
    var detail = new StringBuilder(historyTitle(history)).append('\n').append(locationText(history.location()))
        .append("\n\n確認が必要な履歴 ").append(history.count()).append("件");
    if (history.candidateName() != null) {
      detail.append('\n').append(referencePhoto(history.candidateName()));
      if (history.candidateLocation() != null)
        detail.append('\n').append(locationText(history.candidateLocation()));
    }
    appendReasons(detail, history.reasons());
    return detail.toString();
  }

  private static void appendReasons(StringBuilder detail, List<PhotoStorageInventory.Reason> reasons) {
    for (var reason : reasons) {
      detail.append("\n\n").append(reason.label());
      if (!reason.action().isEmpty()) detail.append('\n').append(reason.action());
    }
  }

  private static String locationText(PhotoStorageLocation location) {
    return location.display() + (location.folder().isEmpty() ? "" : "\nフォルダ　" + location.folder());
  }

  private static String moveStatus(PhotoStorageInventory.Photo photo) {
    return switch (photo.status()) {
      case ELIGIBLE -> "移動対象";
      case ALREADY_THERE -> "設定した保存先にあります";
      case EXCLUDED -> "移動対象外";
    };
  }

  private static String historyTitle(PhotoStorageInventory.History history) {
    return history.name().isEmpty() ? "履歴の名前は不明" : "履歴の名前　" + history.name();
  }

  private static String referencePhoto(String name) { return "参照先の写真　" + name; }

  private TextView text(int size, int color) {
    TextView text = new TextView(this);
    text.setTextSize(size);
    text.setTextColor(color);
    return text;
  }

  private int dp(int value) { return GalleryUi.dp(this, value); }

  private final class InventoryRows extends BaseAdapter {
    private List<PhotoStorageInventory.Photo> photos = List.of();
    private List<PhotoStorageInventory.History> history = List.of();

    void replace(List<PhotoStorageInventory.Photo> photos, List<PhotoStorageInventory.History> history) {
      this.photos = photos;
      this.history = history;
      notifyDataSetChanged();
    }

    @Override public int getCount() { return photos.size() + history.size(); }
    @Override public Object getItem(int position) {
      return position < photos.size() ? photos.get(position) : history.get(position - photos.size());
    }
    @Override public long getItemId(int position) { return position; }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
      RowView row = convertView instanceof RowView recycled ? recycled : new RowView();
      row.bind(getItem(position));
      return row;
    }
  }

  private final class RowView extends LinearLayout {
    private final TextView title, subtitle;

    RowView() {
      super(PhotoStorageInventoryActivity.this);
      setOrientation(VERTICAL);
      setMinimumHeight(dp(48));
      setPadding(dp(20), dp(10), dp(20), dp(10));
      setScreenReaderFocusable(true);
      title = text(16, GalleryUi.TEXT);
      subtitle = text(14, GalleryUi.MUTED);
      title.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
      subtitle.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
      addView(title, new LayoutParams(-1, -2));
      addView(subtitle, new LayoutParams(-1, -2));
    }

    void bind(Object item) {
      String name;
      String description;
      if (item instanceof PhotoStorageInventory.Photo photo) {
        name = photo.historyMatches() ? photo.name() : referencePhoto(photo.name());
        description = locationText(photo.location()) + "\n" + moveStatus(photo);
        if (!photo.reasons().isEmpty()) description += " · " + photo.reasons().get(0).label();
        if (!photo.historyMatches() && photo.recordedName() != null)
          description += "\n履歴の名前　" + photo.recordedName();
      } else {
        var history = (PhotoStorageInventory.History) item;
        name = historyTitle(history);
        description = locationText(history.location()) + "\n" + history.reason().label()
            + " · 履歴 " + history.count() + "件";
        if (history.candidateName() != null) description += "\n" + referencePhoto(history.candidateName());
      }
      title.setText(name);
      subtitle.setText(description);
      setContentDescription(name + "\n" + description);
    }
  }
}
