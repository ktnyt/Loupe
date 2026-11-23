package jp.nano.rawgallery;

import android.app.Activity;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.File;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/** Opens an editor with a separate original-format copy; never grants access to the original. */
final class PhotoEditor {
  static final String PREFERENCES = "photo-editor";
  static final String DEFAULT_APP = "default-app";
  static final String DEFAULT_CERTIFICATES = "default-certificates";
  static final String INTERNAL = "internal";
  static final String INTERNAL_LABEL = "Loupeの内蔵編集";
  static final String PICKER_TITLE = "編集アプリを選択";
  static final String PICKER_ACTION = "編集アプリを選ぶ";
  static final String REMEMBER_LABEL = "次回以降もこのアプリで開く";
  static final String FIRST_USE_HINT = "ロングタップで編集ソフトを変更できます";
  private static final String COPY_URI_PREFIX = "content://jp.nano.rawgallery.share/";

  private final SharedPreferences preferences;
  private final Activity activity;
  private final PhotoViewerUi viewer;
  private final PhotoRepository.Photo photo;
  private final ExecutorService worker;
  private final SharePreparation preparation;
  private final Handler main = new Handler(Looper.getMainLooper());
  final Button button;
  private Request active;
  private AlertDialog picker;
  private Request pending;

  private record Destination(String packageName, String label, Intent intent) {}

  private static final class Request {
    volatile boolean cancelled;
    final String destination;
    final Set<String> certificates;
    final boolean remember;
    final long event = DiagnosticLog.id();
    boolean visible;
    Future<?> task;
    Runnable show;

    Request(String destination, Set<String> certificates, boolean remember) {
      this.destination = destination;
      this.certificates = Set.copyOf(certificates);
      this.remember = remember;
    }
  }

  static Button add(Activity activity, PhotoViewerUi viewer, PhotoRepository.Photo photo,
      ExecutorService worker) {
    Context context = activity.getApplicationContext();
    return new PhotoEditor(activity, viewer, photo, worker,
        new SharePreparation(context, new PhotoRepository(context))).button;
  }

  PhotoEditor(Activity activity, PhotoViewerUi viewer, PhotoRepository.Photo photo,
      ExecutorService worker, SharePreparation preparation) {
    this.activity=activity; this.viewer=viewer; this.photo=photo;
    this.worker=worker; this.preparation=preparation;
    preferences = activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    button = viewer.action("編集", "編集", "手動編集。長押しで編集アプリを選ぶ", () -> activate(false));
    button.setOnLongClickListener(v -> { activate(true); return true; });
    button.setAccessibilityDelegate(new View.AccessibilityDelegate() {
      @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(host, info);
        if (button.isEnabled() && active == null) {
          info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
              AccessibilityNodeInfo.ACTION_LONG_CLICK, PICKER_ACTION));
        }
      }
    });
    button.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    viewer.editFocusChanged = focused -> {
      if (focused) openAfterPicker();
      else cancel(false);
    };
    viewer.editWindowHidden = this::leaveViewer;
    viewer.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
      public void onViewAttachedToWindow(View view) {}
      public void onViewDetachedFromWindow(View view) { leaveViewer(); }
    });
  }

  private static Intent intent(String action, Uri copy, String mime) {
    Intent intent = new Intent(action).setDataAndType(copy, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    intent.setClipData(ClipData.newRawUri("写真", copy));
    return intent;
  }

  private Map<String, Destination> destinations(Uri copy, String mime, String packageName) {
    PackageManager manager = activity.getPackageManager();
    Map<String, Destination> result = new LinkedHashMap<>();
    for (String action : List.of(Intent.ACTION_EDIT, Intent.ACTION_VIEW)) {
      Intent intent = intent(action, copy, mime).setPackage(packageName);
      for (ResolveInfo match : manager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)) {
        ActivityInfo target = match.activityInfo;
        if (target == null || !target.exported || activity.getPackageName().equals(target.packageName)
            || (target.permission != null
                && activity.checkSelfPermission(target.permission) != PackageManager.PERMISSION_GRANTED)
            || result.containsKey(target.packageName)) continue;
        Intent explicit = new Intent(intent)
            .setComponent(new ComponentName(target.packageName, target.name));
        result.put(target.packageName,
            new Destination(target.packageName, target.applicationInfo.loadLabel(manager).toString(), explicit));
      }
    }
    return result;
  }

  private Set<String> signingCertificates(String packageName) {
    try {
      SigningInfo signing = activity.getPackageManager().getPackageInfo(packageName,
          PackageManager.GET_SIGNING_CERTIFICATES).signingInfo;
      if (signing == null) return Set.of();
      Signature[] signers = signing.getApkContentsSigners();
      if (signers == null) return Set.of();
      MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
      Set<String> fingerprints = new HashSet<>();
      for (Signature signer : signers) {
        fingerprints.add(Base64.getEncoder().encodeToString(sha256.digest(signer.toByteArray())));
      }
      return fingerprints;
    } catch (PackageManager.NameNotFoundException | NoSuchAlgorithmException error) {
      return Set.of();
    }
  }

  private boolean sameSigningApp(String packageName, Set<String> selected, Set<String> installed) {
    if (selected.isEmpty() || installed.size() != selected.size()) return false;
    if (selected.equals(installed)) return true;
    // A single signer can rotate through Android's signing history. For APKs with
    // multiple signers the complete current set must match the selected app.
    if (selected.size() != 1) return false;
    try {
      byte[] fingerprint = Base64.getDecoder().decode(selected.iterator().next());
      return fingerprint.length == 32 && activity.getPackageManager().hasSigningCertificate(
          packageName, fingerprint, PackageManager.CERT_INPUT_SHA256);
    } catch (IllegalArgumentException error) {
      return false;
    }
  }

  private boolean viewerAvailable() {
    return !activity.isFinishing() && !activity.isDestroyed() && viewer.isAttachedToWindow()
        && viewer.getWindowVisibility() == View.VISIBLE;
  }

  private void leaveViewer() {
    pending = null;
    if (picker != null) picker.dismiss();
    cancel(false);
  }

  private void openAfterPicker() {
    if (pending == null || picker != null || !viewer.hasWindowFocus()) return;
    Request request = pending;
    pending = null;
    if (viewerAvailable() && button.isEnabled()) prepare(request);
  }

  private void showPicker(boolean longPress, String selected, Set<String> remembered, Uri probe, String mime) {
    List<Destination> apps = new ArrayList<>(destinations(probe, mime, null).values());
    Collator labelOrder = Collator.getInstance();
    apps.sort((first, second) -> labelOrder.compare(first.label(), second.label()));
    Map<String, Integer> labelCounts = new LinkedHashMap<>();
    labelCounts.put(INTERNAL_LABEL, 1);
    for (Destination app : apps) labelCounts.merge(app.label(), 1, Integer::sum);

    MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity);
    Context context = builder.getContext();
    LinearLayout choices = new LinearLayout(context);
    choices.setOrientation(LinearLayout.VERTICAL);
    int horizontal = GalleryUi.dp(context, 24);
    choices.setPadding(horizontal, GalleryUi.dp(context, 8), horizontal, 0);
    MaterialCheckBox remember = new MaterialCheckBox(context);
    remember.setText(REMEMBER_LABEL);
    remember.setMinHeight(GalleryUi.dp(context, 48));
    if (longPress) {
      choices.addView(remember);
    } else {
      TextView help = new TextView(context);
      help.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
      help.setText(FIRST_USE_HINT);
      help.setPadding(0, 0, 0, GalleryUi.dp(context, 8));
      choices.addView(help);
    }

    apps.add(0, new Destination(INTERNAL, INTERNAL_LABEL, null));
    for (Destination app : apps) {
      boolean internal = INTERNAL.equals(app.packageName());
      Set<String> certificates = internal ? Set.of() : signingCertificates(app.packageName());
      if (!internal && certificates.isEmpty()) continue;
      MaterialButton row = MaterialUi.button(context);
      row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
      boolean ambiguous = !internal && labelCounts.get(app.label()) > 1;
      row.setText(ambiguous ? app.label() + "\n" + app.packageName() : app.label());
      if (app.packageName().equals(selected)
          && (internal || sameSigningApp(selected, remembered, certificates))) {
        row.setStateDescription("現在の既定");
      }
      row.setOnClickListener(v -> {
        if (picker == null || pending != null) return;
        pending = new Request(app.packageName(), certificates, !longPress || remember.isChecked());
        picker.dismiss();
      });
      choices.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }
    ScrollView scroll = new ScrollView(context);
    scroll.addView(choices);
    picker = builder.setTitle(PICKER_TITLE).setView(scroll).setNegativeButton("閉じる", null).create();
    picker.setOnDismissListener(dialog -> {
      picker = null;
      openAfterPicker();
    });
    picker.show();
    DiagnosticLog.event("photo.edit.chooser.open", 0, "mode", longPress ? "long-press" : "default");
  }

  private boolean current(Request request) {
    return active == request && !request.cancelled && viewerAvailable();
  }

  private void activate(boolean longPress) {
    if (active != null) {
      if (active.visible && !longPress) cancel(true);
      return;
    }
    if (picker != null || pending != null || !viewerAvailable() || !button.isEnabled()) return;
    String selected = preferences.getString(DEFAULT_APP, null);
    Set<String> certificates = preferences.getStringSet(DEFAULT_CERTIFICATES, Set.of());
    // Same provider and original extension as the copy, with no original URI or filename.
    Uri probe = Uri.parse(COPY_URI_PREFIX + "00000000-0000-0000-0000-000000000000."
        + RawFormats.extension(photo.name));
    String mime = RawFormats.mime(photo.name);
    if (longPress || selected == null
        || (!INTERNAL.equals(selected) && (destinations(probe, mime, selected).isEmpty()
            || !sameSigningApp(selected, certificates, signingCertificates(selected))))) {
      showPicker(longPress, selected, certificates, probe, mime);
      return;
    }
    prepare(new Request(selected, certificates, false));
  }

  private void prepare(Request request) {
    active = request;
    button.setLongClickable(false);
    PhotoViewerUi.editState(button,false,false,"");
    DiagnosticLog.event("photo.edit.prepare.start",request.event,"format",photo.isJpeg()?"JPEG":"RAW");
    request.show=()->{
      if(!current(request))return;
      request.visible=true;PhotoViewerUi.editState(button,true,false,"");
    };
    main.postDelayed(request.show,400);
    try {
      request.task=worker.submit(()->{
        List<File> files=null;
        try {
          SharePreparation.Result prepared=preparation.prepare(List.of(photo),true);
          files=prepared.files;
          if(request.cancelled){discard(files);return;}
          DiagnosticLog.event("photo.edit.prepare.complete",request.event);
          main.post(()->ready(request,prepared));
        } catch(Exception error) {
          discard(files);
          main.post(()->{
            if(!current(request))return;
            finish(request);
            DiagnosticLog.event("photo.edit.prepare.failed",request.event);
            PhotoViewerUi.editState(button,false,true,"編集の準備ができませんでした");
          });
        }
      });
    } catch(java.util.concurrent.RejectedExecutionException error) {
      finish(request);PhotoViewerUi.editState(button,false,true,"編集の準備ができませんでした");
      DiagnosticLog.event("photo.edit.prepare.failed",request.event);
    }
  }

  private void ready(Request request,SharePreparation.Result prepared) {
    if(!current(request)||!viewer.hasWindowFocus()){
      discard(prepared.files);
      if(active==request)cancel(false);
      return;
    }
    finish(request);
    File file = prepared.files.get(0);
    Uri copy = Uri.parse(COPY_URI_PREFIX + file.getName());
    try {
      if (INTERNAL.equals(request.destination)) {
        RawEditActivity.open(activity, copy);
        DiagnosticLog.event("photo.edit.internal.open", request.event);
      } else {
        Destination app = destinations(copy, prepared.mimeType, request.destination).get(request.destination);
        if (app == null || !sameSigningApp(request.destination, request.certificates,
            signingCertificates(request.destination))) throw new ActivityNotFoundException();
        button.requestFocus();
        activity.startActivity(app.intent());
        DiagnosticLog.event("photo.edit.external.open", request.event);
      }
      if (request.remember) {
        preferences.edit().putString(DEFAULT_APP, request.destination)
            .putStringSet(DEFAULT_CERTIFICATES, request.certificates).apply();
      }
    } catch(ActivityNotFoundException error) {
      discard(prepared.files);
      PhotoViewerUi.editState(button, false, true, "この形式で編集アプリを開けませんでした");
      DiagnosticLog.event("photo.edit.chooser.unavailable", request.event);
    } catch(SecurityException error) {
      discard(prepared.files);
      PhotoViewerUi.editState(button,false,true,"編集アプリに写真を渡せませんでした");
      DiagnosticLog.event("photo.edit.chooser.denied",request.event);
    }
  }

  private void finish(Request request) {
    main.removeCallbacks(request.show);
    active = null;
    button.setLongClickable(true);
    PhotoViewerUi.editState(button, false, false, "");
  }

  private void cancel(boolean announce) {
    Request request=active;if(request==null)return;
    request.cancelled=true;
    if(request.task!=null)request.task.cancel(true);
    finish(request);
    DiagnosticLog.event("photo.edit.prepare.cancelled",request.event);
    if(announce)button.announceForAccessibility("編集の準備を中止しました");
  }

  private static void discard(List<File> files) {
    if(files!=null)for(File file:files)file.delete();
  }
}
