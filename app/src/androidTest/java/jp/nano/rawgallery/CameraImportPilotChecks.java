package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import jp.nano.rawgallery.camera.CameraDocumentSource;
import jp.nano.rawgallery.camera.CameraImportFile;
import jp.nano.rawgallery.camera.CameraImportGate;
import jp.nano.rawgallery.camera.CameraImportLedger;
import jp.nano.rawgallery.camera.CameraPhotoImporter;
import jp.nano.rawgallery.camera.CameraPhotoPublisher;
import jp.nano.rawgallery.camera.CameraSourceIdentity;
import jp.nano.rawgallery.camera.CameraWifiNetwork;
import jp.nano.rawgallery.camera.Ep7WifiClient;

/** Small, reversible Wi-Fi/SD pilot. All state is namespaced by a validated run UUID. */
final class CameraImportPilotChecks {
  private static final String INDEX_PREFS = "camera_import_pilot";
  private static final String RUN_PREFS_PREFIX = "camera_import_pilot_";
  private static final String RUN_ID_KEY = "run_id";
  private static final String DATABASE_PREFIX = "camera-import-pilot-";
  private static final String FOLDER_PREFIX = "LoupePilot-";
  private static final long MAX_PILOT_BYTES = 256L * 1024L * 1024L;
  private static final int JPEG_COUNT = 2;
  private static final int RAW_COUNT = 2;
  private static final int MAX_FILES = 4;
  private static final String[] OWNED_MEDIA_FIELDS = {
    MediaStore.MediaColumns._ID,
    MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
    MediaStore.MediaColumns.RELATIVE_PATH
  };

  private CameraImportPilotChecks() {}

  static String runWifi(Instrumentation instrumentation) throws Exception {
    return runWifi(instrumentation, null);
  }

  static String runWifi(
      Instrumentation instrumentation, jp.nano.rawgallery.camera.CameraPairedSession paired)
      throws Exception {
    Context context = instrumentation.getTargetContext();
    if (!CameraImportGate.lock.tryLock()) throw new IOException("Another camera import is active");
    PilotSession session = null;
    boolean success = false;
    try {
      cleanup(instrumentation);
      session = createSession(context);
      ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
      if (connectivity == null) throw new IOException("Wi-Fi network service is unavailable");
      Network network = paired == null ? CameraWifiNetwork.select(connectivity) : paired.network;
      if (network == null)
        throw new IOException("No unambiguous camera Wi-Fi network is available");
      String identity = CameraSourceIdentity.wifi(context, network);
      boolean stable = identity != null;
      String sourceId = stable ? identity : "wifi-session:" + network.getNetworkHandle();
      Ep7WifiClient client =
          paired == null
              ? new Ep7WifiClient(
                  url -> (HttpURLConnection) network.openConnection(url, Proxy.NO_PROXY),
                  () -> false)
              : paired.client;

      long listStart = System.nanoTime();
      Ep7WifiClient.Listing listing = client.list();
      requireNetwork(context, connectivity, network, identity);
      List<Ep7WifiClient.Entry> selected = selectWifi(listing.photos);
      long selectedBytes = totalWifiBytes(selected);
      double listSeconds = elapsed(listStart);
      CameraImportLedger ledger = new CameraImportLedger(context, session.databaseName);
      try {
        CameraPhotoImporter importer = new CameraPhotoImporter(context, ledger);
        importer.recover(() -> false);
        long contentsBefore = ledger.contentCount();
        long receiptsBefore = ledger.receiptCount();
        long firstStart = System.nanoTime();
        long firstBytes = 0;
        int firstPublished = 0;
        List<String> firstUris = new ArrayList<>(selected.size());
        List<String> firstSourceShas = new ArrayList<>(selected.size());
        List<CameraImportFile> sources = new ArrayList<>(selected.size());
        for (Ep7WifiClient.Entry entry : selected) {
          requireNetwork(context, connectivity, network, identity);
          CameraImportFile source = wifiSource(sourceId, entry);
          sources.add(source);
          CameraPhotoImporter.Result result =
              importer.importPhoto(
                  source,
                  out -> copyWifi(context, connectivity, network, identity, client, entry, out),
                  session.folderName,
                  stable,
                  false,
                  () -> false);
          require(
              result.contentChecked && result.sourceBytesRead == source.bytes,
              "Wi-Fi first pass did not read and verify the selected source");
          firstBytes = Math.addExact(firstBytes, result.sourceBytesRead);
          if (result.imported) firstPublished++;
          CameraImportLedger.Content content = ledger.known(source);
          require(
              content != null
                  && content.uri.equals(result.uri.toString())
                  && content.sourceBytes == source.bytes,
              "Wi-Fi first pass did not retain a verified receipt");
          firstSourceShas.add(content.sourceSha);
          firstUris.add(result.uri.toString());
        }
        double firstSeconds = elapsed(firstStart);
        require(firstBytes == selectedBytes, "Wi-Fi first pass byte total changed");

        long historyStart = System.nanoTime();
        long historyBytes = 0;
        for (int i = 0; i < selected.size(); i++) {
          Ep7WifiClient.Entry entry = selected.get(i);
          CameraImportFile source = sources.get(i);
          requireNetwork(context, connectivity, network, identity);
          final int[] copies = {0};
          CameraPhotoImporter.Result result =
              importer.importPhoto(
                  source,
                  out -> {
                    copies[0]++;
                    copyWifi(context, connectivity, network, identity, client, entry, out);
                  },
                  session.folderName,
                  stable,
                  false,
                  () -> false);
          require(
              !result.imported && result.uri.toString().equals(firstUris.get(i)),
              "Wi-Fi history pass created a new publication");
          if (stable) {
            require(
                copies[0] == 0 && result.sourceBytesRead == 0 && !result.contentChecked,
                "Stable Wi-Fi history lookup unexpectedly transferred source bytes");
          } else {
            require(
                copies[0] == 1 && result.sourceBytesRead == source.bytes && result.contentChecked,
                "Unstable Wi-Fi history fallback did not verify the complete source");
          }
          historyBytes = Math.addExact(historyBytes, result.sourceBytesRead);
        }
        double historySeconds = elapsed(historyStart);

        long strictStart = System.nanoTime();
        long strictBytes = 0;
        for (int i = 0; i < selected.size(); i++) {
          Ep7WifiClient.Entry entry = selected.get(i);
          CameraImportFile source = sources.get(i);
          requireNetwork(context, connectivity, network, identity);
          CameraPhotoImporter.Result result =
              importer.importPhoto(
                  source,
                  out -> copyWifi(context, connectivity, network, identity, client, entry, out),
                  session.folderName,
                  stable,
                  true,
                  () -> false);
          require(
              !result.imported
                  && result.contentChecked
                  && result.sourceBytesRead == source.bytes
                  && result.uri.toString().equals(firstUris.get(i)),
              "Wi-Fi strict pass did not verify the existing publication");
          strictBytes = Math.addExact(strictBytes, result.sourceBytesRead);
        }
        double strictSeconds = elapsed(strictStart);
        require(ledger.pending().isEmpty(), "Wi-Fi pilot left pending publications");
        long newContents = ledger.contentCount() - contentsBefore;
        long newReceipts = ledger.receiptCount() - receiptsBefore;
        saveWifiResults(session, stable, sources, firstSourceShas, firstUris);
        success = true;
        return "PASS pilot-wifi"
            + " stable="
            + stable
            + " listFiles="
            + listing.photos.size()
            + " selectedFiles="
            + selected.size()
            + " bytes="
            + selectedBytes
            + " listSeconds="
            + metric(listSeconds)
            + " firstSeconds="
            + metric(firstSeconds)
            + " firstBytes="
            + firstBytes
            + " firstPublished="
            + firstPublished
            + " newContents="
            + newContents
            + " newReceipts="
            + newReceipts
            + " historySeconds="
            + metric(historySeconds)
            + " historyBytes="
            + historyBytes
            + " strictSeconds="
            + metric(strictSeconds)
            + " strictBytes="
            + strictBytes;
      } finally {
        ledger.close();
      }
    } finally {
      try {
        if (!success && session != null) cleanupSession(context, session);
      } finally {
        CameraImportGate.lock.unlock();
      }
    }
  }

  /**
   * Read representations only: no importer, publication, receipt, ledger or failed-journal writes.
   */
  static String runSdSourceDiagnostic(Instrumentation instrumentation) throws IOException {
    throw new IOException("DIAGNOSTIC_TARGET_REQUIRED");
  }

  static String runSdSourceDiagnostic(Instrumentation instrumentation, Bundle arguments)
      throws IOException {
    if (arguments == null) throw new IOException("DIAGNOSTIC_TARGET_REQUIRED");
    try {
      return runSdSourceDiagnostic(
          instrumentation,
          Integer.parseInt(arguments.getString("diagnosticIndex", "-1")),
          Long.parseLong(arguments.getString("diagnosticBytes", "-1")),
          arguments.getString("diagnosticRunFingerprint"));
    } catch (NumberFormatException invalid) {
      throw new IOException("DIAGNOSTIC_TARGET_REQUIRED");
    }
  }

  static String runSdSourceDiagnostic(
      Instrumentation instrumentation,
      int targetIndex,
      long expectedBytes,
      String expectedRunFingerprint)
      throws IOException {
    Context context = instrumentation.getTargetContext();
    if (!CameraImportGate.lock.tryLock()) throw new IOException("DIAGNOSTIC_GATE_BUSY");
    try {
      PilotSession session;
      List<WifiProof> proof;
      try {
        session = loadCurrentSession(context);
        if (session == null || !context.getDatabasePath(session.databaseName).exists())
          throw new IOException("DIAGNOSTIC_MISSING_PROOF");
        validateSession(session);
        proof = loadWifiProof(session);
        if (proof.size() != MAX_FILES) throw new IOException("DIAGNOSTIC_MISSING_PROOF");
        int jpegs = 0, raws = 0;
        for (WifiProof file : proof) {
          if (isJpeg(file.relativePath)) jpegs++;
          else if (isRaw(file.relativePath)) raws++;
        }
        if (jpegs != JPEG_COUNT || raws != RAW_COUNT)
          throw new IOException("DIAGNOSTIC_MISSING_PROOF");
      } catch (IOException | RuntimeException invalid) {
        throw new IOException("DIAGNOSTIC_MISSING_PROOF");
      }
      List<Integer> indexes =
          diagnosticIndexes(
              session.runId, proof, targetIndex, expectedBytes, expectedRunFingerprint);
      String treeValue =
          context
              .getSharedPreferences("camera_import", Context.MODE_PRIVATE)
              .getString("sd_tree_uri", null);
      Uri tree;
      try {
        tree = treeValue == null ? null : Uri.parse(treeValue);
        if (tree == null || !DocumentsContract.isTreeUri(tree))
          throw new IllegalArgumentException();
        boolean retained = false;
        for (android.content.UriPermission permission :
            context.getContentResolver().getPersistedUriPermissions())
          if (permission.isReadPermission() && tree.equals(permission.getUri())) retained = true;
        if (!retained) throw new IllegalArgumentException();
      } catch (RuntimeException invalid) {
        throw new IOException("DIAGNOSTIC_SD_TREE_UNAVAILABLE");
      }
      List<CameraDocumentSource.Entry> selected;
      try {
        selected =
            selectSdForWifi(
                CameraDocumentSource.list(
                        context, tree, () -> Thread.currentThread().isInterrupted())
                    .photos,
                proof);
      } catch (IOException | RuntimeException failure) {
        throw new IOException("DIAGNOSTIC_SD_SELECTION_UNAVAILABLE");
      }
      long selectedBytes = 0;
      for (int index : indexes)
        selectedBytes = Math.addExact(selectedBytes, proof.get(index).bytes);
      if (selectedBytes > MAX_PILOT_BYTES) throw new IOException("DIAGNOSTIC_READ_BUDGET");
      boolean permission =
          context.checkSelfPermission(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
              == android.content.pm.PackageManager.PERMISSION_GRANTED;
      SharedPreferences baseline =
          context.getSharedPreferences(
              "camera_sd_source_diagnostic_baseline", Context.MODE_PRIVATE);
      DiagnosticBudget budget = new DiagnosticBudget();
      StringBuilder report = new StringBuilder();
      int files = 0;
      boolean needsPermission = !permission;
      for (int index : indexes) {
        CameraDocumentSource.Entry entry = selected.get(index);
        WifiProof expected = proof.get(index);
        DiagnosticRead first = diagnosticDefault(context, entry.uri, expected.bytes, budget);
        DiagnosticRead repeat =
            first.valid()
                ? diagnosticDefault(context, entry.uri, expected.bytes, budget)
                : DiagnosticRead.unread();
        boolean stable = first.valid() && repeat.valid() && first.sha.equals(repeat.sha);
        String reason =
            !first.valid()
                ? first.reason
                : !repeat.valid() ? repeat.reason : !stable ? "DEFAULT_UNSTABLE" : "NONE";
        boolean mapped = false, originalAvailable = false, originalMatches = false;
        DiagnosticRead original = DiagnosticRead.unread();
        boolean baselineAvailable = false,
            defaultMatchesBaseline = false,
            originalMatchesBaseline = false;
        if (stable) {
          String baselineSha = diagnosticBaseline(baseline, session, index, expected);
          baselineAvailable = baselineSha != null;
          if (!permission) {
            // Preserve the earliest stable, proof-bound pre-permission representation.
            if (!baselineAvailable) {
              SharedPreferences.Editor editor = baseline.edit();
              if (!session.runId.equals(baseline.getString("run_id", null))) editor.clear();
              editor
                  .putString("run_id", session.runId)
                  .putString("proof_" + index, expected.sourceSha)
                  .putLong("bytes_" + index, expected.bytes)
                  .putString("default_" + index, first.sha);
              if (!editor.commit()) {
                reason = "BASELINE_SAVE_FAILED";
                needsPermission = false;
              } else baselineAvailable = true;
            }
          } else if (baselineAvailable) defaultMatchesBaseline = baselineSha.equals(first.sha);
          Uri media = null;
          try {
            media = MediaStore.getMediaUri(context, entry.uri);
          } catch (RuntimeException unavailable) {
            /* Closed category below; never use another URI. */
          }
          mapped = media != null;
          if (!mapped && reason.equals("NONE")) reason = "MAPPING_UNAVAILABLE";
          else if (!permission && reason.equals("NONE")) reason = "PERMISSION_DENIED";
          else if (mapped && permission) {
            original = diagnosticOriginal(context, media, expected.bytes, budget);
            originalAvailable = original.valid();
            originalMatches = originalAvailable && original.sha.equals(expected.sourceSha);
            if (baselineAvailable && originalAvailable)
              originalMatchesBaseline = baselineSha.equals(original.sha);
            if (!originalAvailable && reason.equals("NONE")) reason = original.reason;
          }
        }
        String key = " file" + files;
        report
            .append(key)
            .append("Index=")
            .append(index)
            .append(key)
            .append("Kind=")
            .append(isRaw(entry.file.name) ? "RAW" : "JPEG")
            .append(key)
            .append("ExpectedBytes=")
            .append(expected.bytes)
            .append(key)
            .append("DefaultRead1=")
            .append(first.bytes)
            .append(key)
            .append("DefaultRead2=")
            .append(repeat.bytes)
            .append(key)
            .append("RepeatStable=")
            .append(stable)
            .append(key)
            .append("DefaultMatches=")
            .append(stable && first.sha.equals(expected.sourceSha))
            .append(key)
            .append("Mapped=")
            .append(mapped)
            .append(key)
            .append("OriginalAvailable=")
            .append(originalAvailable)
            .append(key)
            .append("OriginalRead=")
            .append(original.bytes)
            .append(key)
            .append("OriginalMatches=")
            .append(originalMatches)
            .append(key)
            .append("BaselineAvailable=")
            .append(baselineAvailable)
            .append(key)
            .append("DefaultMatchesBaseline=")
            .append(defaultMatchesBaseline)
            .append(key)
            .append("OriginalMatchesBaseline=")
            .append(originalMatchesBaseline)
            .append(key)
            .append("Reason=")
            .append(reason);
        files++;
        if (!stable) {
          needsPermission = false;
          break;
        } // Do not interpret mapping/originals or other files after unstable reads.
      }
      return "DIAGNOSTIC sd-source permission="
          + permission
          + " needsPermission="
          + needsPermission
          + " files="
          + files
          + " readBytes="
          + budget.used
          + report;
    } finally {
      CameraImportGate.lock.unlock();
    }
  }

  private static List<Integer> diagnosticIndexes(
      String runId,
      List<WifiProof> proof,
      int targetIndex,
      long expectedBytes,
      String expectedRunFingerprint)
      throws IOException {
    if (targetIndex < 0
        || targetIndex >= proof.size()
        || expectedBytes <= 0
        || !validSha(expectedRunFingerprint)) throw new IOException("DIAGNOSTIC_TARGET_REQUIRED");
    if (!diagnosticRunFingerprint(runId).equals(expectedRunFingerprint)
        || proof.get(targetIndex).bytes != expectedBytes)
      throw new IOException("DIAGNOSTIC_TARGET_MISMATCH");
    List<Integer> indexes = new ArrayList<>();
    indexes.add(targetIndex);
    for (int i = 0; i < proof.size(); i++)
      if (isRaw(proof.get(i).relativePath)) {
        if (i != targetIndex) indexes.add(i);
        break;
      }
    return indexes;
  }

  private static String diagnosticRunFingerprint(String runId) {
    try {
      byte[] digest =
          java.security.MessageDigest.getInstance("SHA-256")
              .digest(runId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder value = new StringBuilder();
      try {
        for (byte item : digest) value.append(String.format(Locale.ROOT, "%02x", item & 255));
      } finally {
        java.util.Arrays.fill(digest, (byte) 0);
      }
      return value.toString();
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static String diagnosticBaseline(
      SharedPreferences baseline, PilotSession session, int index, WifiProof proof) {
    if (!session.runId.equals(baseline.getString("run_id", null))
        || !proof.sourceSha.equals(baseline.getString("proof_" + index, null))
        || proof.bytes != baseline.getLong("bytes_" + index, -1)) return null;
    String sha = baseline.getString("default_" + index, null);
    return validSha(sha) ? sha : null;
  }

  private static DiagnosticRead diagnosticDefault(
      Context context, Uri uri, long bytes, DiagnosticBudget budget) {
    return diagnosticDescriptor(context, uri, bytes, budget, "DEFAULT");
  }

  private static DiagnosticRead diagnosticOriginal(
      Context context, Uri media, long bytes, DiagnosticBudget budget) {
    try {
      return diagnosticDescriptor(
          context, MediaStore.setRequireOriginal(media), bytes, budget, "ORIGINAL");
    } catch (RuntimeException unavailable) {
      return new DiagnosticRead(0, null, "ORIGINAL_UNAVAILABLE");
    }
  }

  private static DiagnosticRead diagnosticDescriptor(
      Context context, Uri uri, long bytes, DiagnosticBudget budget, String kind) {
    android.os.CancellationSignal signal = new android.os.CancellationSignal();
    DiagnosticDeadline deadline = new DiagnosticDeadline(120000, signal::cancel);
    DiagnosticRead result = DiagnosticRead.unread();
    try {
      java.io.InputStream input = null;
      if (kind.equals("DEFAULT")) {
        // openInputStream uses the asset-descriptor path; preserve that representation while
        // adding the cancellation signal omitted by the convenience API.
        android.content.res.AssetFileDescriptor descriptor =
            context.getContentResolver().openAssetFileDescriptor(uri, "r", signal);
        if (descriptor != null) {
          deadline.attach(descriptor);
          if (deadline.reason() == null) input = descriptor.createInputStream();
        }
      } else {
        android.os.ParcelFileDescriptor descriptor =
            context.getContentResolver().openFileDescriptor(uri, "r", signal);
        if (descriptor != null) {
          deadline.attach(descriptor);
          if (deadline.reason() == null)
            input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor);
        }
      }
      if (input == null) result = new DiagnosticRead(0, null, kind + "_UNAVAILABLE");
      else {
        deadline.attach(input);
        result = diagnosticDigest(input, bytes, budget, kind, deadline);
      }
    } catch (SecurityException denied) {
      result = new DiagnosticRead(result.bytes, null, kind + "_PERMISSION");
    } catch (IOException | RuntimeException unavailable) {
      result = new DiagnosticRead(result.bytes, null, kind + "_UNAVAILABLE");
    } finally {
      deadline.close();
    }
    String stopped = deadline.reason();
    if (stopped != null) return new DiagnosticRead(result.bytes, null, stopped);
    if (deadline.closeFailed && result.valid())
      return new DiagnosticRead(result.bytes, null, kind + "_CLOSE");
    return result;
  }

  private static DiagnosticRead diagnosticDigest(
      java.io.InputStream input, long expected, DiagnosticBudget budget, String kind) {
    return diagnosticDigestOwned(input, expected, budget, kind, 120000);
  }

  private static DiagnosticRead diagnosticDigestOwned(
      java.io.InputStream input,
      long expected,
      DiagnosticBudget budget,
      String kind,
      long timeoutMillis) {
    DiagnosticDeadline deadline = new DiagnosticDeadline(timeoutMillis, () -> {});
    DiagnosticRead result;
    deadline.attach(input);
    try {
      result = diagnosticDigest(input, expected, budget, kind, deadline);
    } finally {
      deadline.close();
    }
    String stopped = deadline.reason();
    if (stopped != null) return new DiagnosticRead(result.bytes, null, stopped);
    if (deadline.closeFailed && result.valid())
      return new DiagnosticRead(result.bytes, null, kind + "_CLOSE");
    return result;
  }

  private static DiagnosticRead diagnosticDigest(
      java.io.InputStream input,
      long expected,
      DiagnosticBudget budget,
      String kind,
      DiagnosticDeadline deadline) {
    byte[] buffer = new byte[65536];
    long count = 0;
    try {
      java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
      while (true) {
        String stopped = deadline.reason();
        if (stopped != null) return new DiagnosticRead(count, null, stopped);
        long room = Math.min(expected + 1 - count, MAX_PILOT_BYTES - budget.used);
        if (room <= 0) return new DiagnosticRead(count, null, "READ_BUDGET");
        int read = input.read(buffer, 0, (int) Math.min(buffer.length, room));
        if (read > 0) {
          count += read;
          budget.used += read;
        }
        stopped = deadline.reason(); // A late read/EOF must never become a successful digest.
        if (stopped != null) return new DiagnosticRead(count, null, stopped);
        if (read < 0) break;
        if (read == 0) continue;
        if (count > expected) return new DiagnosticRead(count, null, kind + "_LENGTH");
        digest.update(buffer, 0, read);
      }
      if (count != expected) return new DiagnosticRead(count, null, kind + "_LENGTH");
      StringBuilder sha = new StringBuilder();
      byte[] hash = digest.digest();
      try {
        for (byte value : hash) sha.append(String.format(Locale.ROOT, "%02x", value & 255));
      } finally {
        java.util.Arrays.fill(hash, (byte) 0);
      }
      String stopped = deadline.reason();
      return stopped == null
          ? new DiagnosticRead(count, sha.toString(), "NONE")
          : new DiagnosticRead(count, null, stopped);
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    } catch (SecurityException denied) {
      return new DiagnosticRead(count, null, kind + "_PERMISSION");
    } catch (IOException | RuntimeException failure) {
      String stopped = deadline.reason();
      return new DiagnosticRead(count, null, stopped == null ? kind + "_READ" : stopped);
    } finally {
      java.util.Arrays.fill(buffer, (byte) 0);
    }
  }

  /**
   * Cancels provider open and closes an attached descriptor/stream on deadline or owner
   * interruption.
   */
  private static final class DiagnosticDeadline implements AutoCloseable {
    final Thread owner = Thread.currentThread();
    final long until;
    final Runnable cancelProvider;
    final java.util.concurrent.atomic.AtomicReference<java.io.Closeable> resource =
        new java.util.concurrent.atomic.AtomicReference<>();
    final java.util.concurrent.atomic.AtomicReference<String> stopped =
        new java.util.concurrent.atomic.AtomicReference<>();
    final java.util.concurrent.ScheduledExecutorService timer;
    volatile boolean finished, closeFailed;

    DiagnosticDeadline(long timeoutMillis, Runnable cancelProvider) {
      this.until =
          System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
      this.cancelProvider = cancelProvider;
      timer =
          java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
              task -> {
                Thread thread = new Thread(task, "sd-source-diagnostic-deadline");
                thread.setDaemon(true);
                return thread;
              });
      timer.scheduleWithFixedDelay(
          () -> {
            if (!finished && reason() != null) {
              closeResource();
              try {
                cancelProvider.run();
              } catch (RuntimeException ignored) {
              }
            }
          },
          Math.min(10, timeoutMillis),
          10,
          java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    String reason() {
      if (owner.isInterrupted()) stopped.compareAndSet(null, "READ_CANCELLED");
      if (System.nanoTime() >= until) stopped.compareAndSet(null, "READ_TIMEOUT");
      return stopped.get();
    }

    void attach(java.io.Closeable value) {
      resource.set(value);
      if (reason() != null) closeResource();
    }

    private void closeResource() {
      java.io.Closeable value = resource.getAndSet(null);
      if (value != null)
        try {
          value.close();
        } catch (IOException | RuntimeException failed) {
          closeFailed = true;
        }
    }

    @Override
    public void close() {
      closeResource();
      reason(); // Closing itself may have crossed the deadline.
      finished = true;
      timer.shutdownNow();
    }
  }

  /** Synthetic stream bounds only; does not establish anything about Android redaction. */
  static String runSdSourceDiagnosticSyntheticChecks() throws IOException {
    byte[] value = {97, 98, 99};
    DiagnosticRead first =
        diagnosticDigest(
            new java.io.ByteArrayInputStream(value), 3, new DiagnosticBudget(), "DEFAULT");
    require(
        first.valid()
            && first.bytes == 3
            && first.sha.equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
        "Synthetic SHA vector");
    DiagnosticRead shortRead =
        diagnosticDigest(
            new java.io.ByteArrayInputStream(value), 4, new DiagnosticBudget(), "DEFAULT");
    require(
        !shortRead.valid() && shortRead.bytes == 3 && shortRead.reason.equals("DEFAULT_LENGTH"),
        "Short source rejected");
    DiagnosticRead surplus =
        diagnosticDigest(
            new java.io.ByteArrayInputStream(new byte[100]), 3, new DiagnosticBudget(), "DEFAULT");
    require(
        !surplus.valid() && surplus.bytes == 4 && surplus.reason.equals("DEFAULT_LENGTH"),
        "Surplus bounded at expected plus one");
    DiagnosticBudget budget = new DiagnosticBudget();
    budget.used = MAX_PILOT_BYTES - 2;
    DiagnosticRead bounded =
        diagnosticDigest(new java.io.ByteArrayInputStream(value), 3, budget, "DEFAULT");
    require(
        !bounded.valid()
            && bounded.bytes == 2
            && budget.used == MAX_PILOT_BYTES
            && bounded.reason.equals("READ_BUDGET"),
        "Shared read budget enforced");
    final byte[][] observed = {null};
    java.io.InputStream failing =
        new java.io.InputStream() {
          int reads;

          public int read() {
            throw new AssertionError("Bulk read expected");
          }

          @Override
          public int read(byte[] buffer, int offset, int count) throws IOException {
            observed[0] = buffer;
            if (reads++ > 0) throw new IOException("Synthetic failure");
            buffer[offset] = 97;
            return 1;
          }
        };
    DiagnosticRead failed = diagnosticDigest(failing, 3, new DiagnosticBudget(), "DEFAULT");
    require(
        !failed.valid() && failed.bytes == 1 && failed.reason.equals("DEFAULT_READ"),
        "Read failure retains observed byte count");
    require(observed[0] != null, "Read buffer observed");
    for (byte item : observed[0]) require(item == 0, "Read buffer erased after failure");
    java.io.InputStream untilClosed =
        new java.io.InputStream() {
          boolean closed;

          public int read() {
            throw new AssertionError("Bulk read expected");
          }

          @Override
          public synchronized int read(byte[] buffer, int offset, int count) throws IOException {
            while (!closed)
              try {
                wait();
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted");
              }
            throw new IOException("Closed");
          }

          @Override
          public synchronized void close() {
            closed = true;
            notifyAll();
          }
        };
    long timeoutStart = System.nanoTime();
    DiagnosticRead timed =
        diagnosticDigestOwned(untilClosed, 3, new DiagnosticBudget(), "DEFAULT", 40);
    require(
        !timed.valid()
            && timed.reason.equals("READ_TIMEOUT")
            && System.nanoTime() - timeoutStart < 2_000_000_000L,
        "Deadline closes blocked stream");
    java.util.concurrent.CountDownLatch reading = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicBoolean cancelledClosed =
        new java.util.concurrent.atomic.AtomicBoolean();
    java.util.concurrent.atomic.AtomicReference<DiagnosticRead> cancelledResult =
        new java.util.concurrent.atomic.AtomicReference<>();
    java.io.InputStream untilCancelledClose =
        new java.io.InputStream() {
          public int read() {
            throw new AssertionError("Bulk read expected");
          }

          @Override
          public int read(byte[] buffer, int offset, int count) throws IOException {
            reading.countDown();
            while (!cancelledClosed.get())
              java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
            throw new IOException("Closed");
          }

          @Override
          public void close() {
            cancelledClosed.set(true);
          }
        };
    Thread interruptedReader =
        new Thread(
            () ->
                cancelledResult.set(
                    diagnosticDigestOwned(
                        untilCancelledClose, 3, new DiagnosticBudget(), "DEFAULT", 5000)));
    interruptedReader.setDaemon(true);
    interruptedReader.start();
    try {
      require(
          reading.await(2, java.util.concurrent.TimeUnit.SECONDS), "Cancellation reader started");
      interruptedReader.interrupt();
      interruptedReader.join(2000);
      require(
          !interruptedReader.isAlive()
              && cancelledClosed.get()
              && cancelledResult.get() != null
              && cancelledResult.get().reason.equals("READ_CANCELLED"),
          "Owner cancellation closes blocked stream");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("Synthetic cancellation test interrupted");
    } finally {
      untilCancelledClose.close();
    }
    java.io.InputStream lateEof =
        new java.io.ByteArrayInputStream(value) {
          boolean ended;

          @Override
          public int read(byte[] buffer, int offset, int count) {
            if (!ended) {
              ended = true;
              return super.read(buffer, offset, count);
            }
            long until = System.nanoTime() + 80_000_000L;
            while (System.nanoTime() < until) Thread.yield();
            return -1;
          }
        };
    DiagnosticRead late = diagnosticDigestOwned(lateEof, 3, new DiagnosticBudget(), "DEFAULT", 40);
    require(
        !late.valid() && late.bytes == 3 && late.reason.equals("READ_TIMEOUT"),
        "Late EOF never succeeds");
    java.util.List<WifiProof> targets =
        java.util.Arrays.asList(
            new WifiProof("DCIM/first.JPG", 3, first.sha, ""),
            new WifiProof("DCIM/second.JPG", 4, first.sha, ""),
            new WifiProof("DCIM/control.ORF", 5, first.sha, ""));
    String runFingerprint = diagnosticRunFingerprint("synthetic-run");
    require(
        diagnosticIndexes("synthetic-run", targets, 1, 4, runFingerprint)
            .equals(java.util.Arrays.asList(1, 2)),
        "Explicit index1 is selected before RAW control");
    try {
      diagnosticIndexes("synthetic-run", targets, 1, 3, runFingerprint);
      throw new AssertionError("Wrong target size accepted");
    } catch (IOException expected) {
    }
    try {
      diagnosticIndexes("other-run", targets, 1, 4, runFingerprint);
      throw new AssertionError("Different target run accepted");
    } catch (IOException expected) {
    }
    try {
      diagnosticIndexes("synthetic-run", targets, -1, -1, null);
      throw new AssertionError("Implicit target accepted");
    } catch (IOException expected) {
    }
    java.util.Map<String, Object> stored = new java.util.HashMap<>();
    SharedPreferences baseline =
        (SharedPreferences)
            java.lang.reflect.Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(),
                new Class<?>[] {SharedPreferences.class},
                (proxy, method, arguments) -> {
                  if (method.getName().equals("getString") || method.getName().equals("getLong"))
                    return stored.containsKey(arguments[0])
                        ? stored.get(arguments[0])
                        : arguments[1];
                  throw new AssertionError("Synthetic baseline must remain read-only");
                });
    PilotSession session = new PilotSession("synthetic-run", "", "", "", null);
    WifiProof proof = new WifiProof("synthetic", 3, first.sha, "");
    stored.put("run_id", session.runId);
    stored.put("proof_0", first.sha);
    stored.put("bytes_0", 3L);
    stored.put("default_0", first.sha);
    require(
        first.sha.equals(diagnosticBaseline(baseline, session, 0, proof)),
        "Matching baseline available");
    require(
        diagnosticBaseline(baseline, session, 1, proof) == null, "Different proof index rejected");
    stored.put("run_id", "different-run");
    require(
        diagnosticBaseline(baseline, session, 0, proof) == null, "Different run baseline rejected");
    stored.put("run_id", session.runId);
    stored.put("proof_0", "different-proof");
    require(
        diagnosticBaseline(baseline, session, 0, proof) == null, "Different source proof rejected");
    stored.put("proof_0", first.sha);
    stored.put("bytes_0", 4L);
    require(
        diagnosticBaseline(baseline, session, 0, proof) == null, "Different source size rejected");
    return "PASS sd-source-diagnostic-synthetic digest=true shortRejected=true surplusBounded=true sharedBudget=true failedCount=true bufferErased=true baselineGuards=true explicitTarget=true blockedReadClosed=true interruptedReadClosed=true lateEofRejected=true";
  }

  private static final class DiagnosticBudget {
    long used;
  }

  private static final class DiagnosticRead {
    final long bytes;
    final String sha, reason;

    DiagnosticRead(long bytes, String sha, String reason) {
      this.bytes = bytes;
      this.sha = sha;
      this.reason = reason;
    }

    boolean valid() {
      return sha != null && reason.equals("NONE");
    }

    static DiagnosticRead unread() {
      return new DiagnosticRead(0, null, "NOT_READ");
    }
  }

  static String runSd(Instrumentation instrumentation) throws Exception {
    Context context = instrumentation.getTargetContext();
    if (!CameraImportGate.lock.tryLock()) throw new IOException("Another camera import is active");
    PilotSession session = null;
    boolean success = false;
    try {
      session = loadCurrentSession(context);
      if (session == null || !context.getDatabasePath(session.databaseName).exists()) {
        throw new IOException("Wi-Fi pilot baseline is missing; run Wi-Fi pilot first");
      }
      List<WifiProof> wifiProof = loadWifiProof(session);
      if (wifiProof.size() != MAX_FILES) throw new IOException("Wi-Fi pilot proof is incomplete");
      String treeValue =
          context
              .getSharedPreferences("camera_import", Context.MODE_PRIVATE)
              .getString("sd_tree_uri", null);
      if (treeValue == null) throw new IOException("No saved SD card tree is available");
      Uri tree = Uri.parse(treeValue);
      if (!DocumentsContract.isTreeUri(tree))
        throw new IOException("Saved SD card tree is invalid");

      CameraImportLedger ledger = new CameraImportLedger(context, session.databaseName);
      try {
        CameraPhotoImporter importer = new CameraPhotoImporter(context, ledger);
        importer.recover(() -> false);
        long scanStart = System.nanoTime();
        CameraDocumentSource.Listing listing =
            CameraDocumentSource.list(context, tree, () -> false);
        boolean crossRoute = true;
        List<CameraDocumentSource.Entry> selected = selectSdForWifi(listing.photos, wifiProof);
        long selectedBytes = totalSdBytes(selected);
        double scanSeconds = elapsed(scanStart);
        long contentsBefore = ledger.contentCount();
        long receiptsBefore = ledger.receiptCount();
        List<String> firstUris = new ArrayList<>(selected.size());
        long firstStart = System.nanoTime();
        long firstBytes = 0;
        int firstPublished = 0;
        int crossMatches = 0;
        for (int i = 0; i < selected.size(); i++) {
          CameraDocumentSource.Entry entry = selected.get(i);
          WifiProof expectedProof = wifiProof.get(i);
          CameraPhotoImporter.Result result =
              importer.importPhoto(
                  entry.file,
                  out -> copySdChecked(context, entry, out, expectedProof.sourceSha),
                  session.folderName,
                  listing.stable,
                  true,
                  () -> false);
          require(
              result.contentChecked && result.sourceBytesRead == entry.file.bytes,
              "SD first pass did not read and verify the selected source");
          firstBytes = Math.addExact(firstBytes, result.sourceBytesRead);
          if (result.imported) firstPublished++;
          if (crossRoute) {
            WifiProof proof = wifiProof.get(i);
            require(
                !result.imported && result.uri.toString().equals(proof.uri),
                "SD source content did not match the Wi-Fi publication");
            CameraImportLedger.Content known = ledger.known(entry.file);
            require(
                known != null
                    && known.sourceSha.equals(proof.sourceSha)
                    && known.uri.equals(proof.uri),
                "SD receipt did not bind to the Wi-Fi original");
            crossMatches++;
          }
          firstUris.add(result.uri.toString());
        }
        double firstSeconds = elapsed(firstStart);
        require(firstBytes == selectedBytes, "SD first pass byte total changed");
        long newContents = ledger.contentCount() - contentsBefore;
        long newReceipts = ledger.receiptCount() - receiptsBefore;
        if (crossRoute && (crossMatches != MAX_FILES || newContents != 0 || firstPublished != 0)) {
          throw new IOException("Cross-route pilot did not verify all Wi-Fi originals");
        }

        long historyStart = System.nanoTime();
        long historyBytes = 0;
        for (int i = 0; i < selected.size(); i++) {
          CameraDocumentSource.Entry entry = selected.get(i);
          final int[] copies = {0};
          WifiProof expectedProof = wifiProof.get(i);
          CameraPhotoImporter.Result result =
              importer.importPhoto(
                  entry.file,
                  out -> {
                    copies[0]++;
                    copySdChecked(context, entry, out, expectedProof.sourceSha);
                  },
                  session.folderName,
                  listing.stable,
                  false,
                  () -> false);
          require(
              !result.imported && result.uri.toString().equals(firstUris.get(i)),
              "SD history pass created a new publication");
          if (listing.stable)
            require(
                copies[0] == 0 && result.sourceBytesRead == 0 && !result.contentChecked,
                "Stable SD history pass reread the source");
          else
            require(
                copies[0] == 1
                    && result.sourceBytesRead == entry.file.bytes
                    && result.contentChecked,
                "Unstable SD history fallback did not verify source content");
          if (crossRoute) {
            WifiProof proof = wifiProof.get(i);
            CameraImportLedger.Content known = ledger.known(entry.file);
            require(
                result.uri.toString().equals(proof.uri)
                    && known != null
                    && known.sourceSha.equals(proof.sourceSha)
                    && known.uri.equals(proof.uri),
                "SD history pass did not retain the Wi-Fi content binding");
          }
          historyBytes = Math.addExact(historyBytes, result.sourceBytesRead);
        }
        double historySeconds = elapsed(historyStart);
        require(ledger.pending().isEmpty(), "SD pilot left pending publications");
        require(
            ledger.contentCount() == contentsBefore + newContents
                && ledger.receiptCount() == receiptsBefore + newReceipts,
            "SD history pass changed content or receipt counts");
        success = true;
        return "PASS pilot-sd"
            + " crossRouteVerified="
            + crossRoute
            + " stable="
            + listing.stable
            + " selectedFiles="
            + selected.size()
            + " bytes="
            + selectedBytes
            + " scanSeconds="
            + metric(scanSeconds)
            + " firstSeconds="
            + metric(firstSeconds)
            + " firstBytes="
            + firstBytes
            + " firstPublished="
            + firstPublished
            + " crossMatches="
            + crossMatches
            + " newContents="
            + newContents
            + " newReceipts="
            + newReceipts
            + " historySeconds="
            + metric(historySeconds)
            + " historyBytes="
            + historyBytes;
      } finally {
        ledger.close();
      }
    } finally {
      try {
        if (success && session != null) cleanupSession(context, session);
      } finally {
        CameraImportGate.lock.unlock();
      }
    }
  }

  // A mismatch throws before importPhoto can publish or commit a source receipt.
  private static void copySdChecked(
      Context context,
      CameraDocumentSource.Entry entry,
      java.io.OutputStream output,
      String expectedSha)
      throws IOException {
    try {
      java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
      java.security.DigestOutputStream checked =
          new java.security.DigestOutputStream(output, digest);
      CameraDocumentSource.copy(context, entry, checked, () -> false);
      StringBuilder actual = new StringBuilder();
      for (byte value : digest.digest())
        actual.append(String.format(Locale.ROOT, "%02x", value & 255));
      require(expectedSha.equals(actual.toString()), "SD source SHA does not match Wi-Fi proof");
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  static String runSdGuardChecks(Instrumentation instrumentation) throws Exception {
    Context context = instrumentation.getTargetContext();
    require(loadCurrentSession(context) == null, "Guard check requires no active pilot");
    try {
      runSd(instrumentation);
      throw new AssertionError("Missing baseline was accepted");
    } catch (IOException expected) {
      require(loadCurrentSession(context) == null, "Missing baseline created a run");
    }
    PilotSession session = createSession(context);
    try {
      try (CameraImportLedger ledger = new CameraImportLedger(context, session.databaseName)) {
        ledger.getWritableDatabase();
      }
      require(
          context.getDatabasePath(session.databaseName).exists(),
          "Guard baseline database was not created");
      try {
        runSd(instrumentation);
        throw new AssertionError("Incomplete proof was accepted");
      } catch (IOException expected) {
        PilotSession retained = loadCurrentSession(context);
        require(
            retained != null
                && retained.runId.equals(session.runId)
                && context.getDatabasePath(session.databaseName).exists(),
            "Failed SD erased baseline");
      }
      return "PASS pilot-sd-guards missingBaselineRejected=true failedProofPreserved=true";
    } finally {
      cleanupSession(context, session);
    }
  }

  static String cleanup(Instrumentation instrumentation) throws Exception {
    Context context = instrumentation.getTargetContext();
    if (!CameraImportGate.lock.tryLock()) throw new IOException("Another camera import is active");
    try {
      PilotSession session = loadCurrentSession(context);
      if (session == null) return "PASS pilot-cleanup no-owned-run";
      int deleted = cleanupSession(context, session);
      return "PASS pilot-cleanup deletedOwnedRows=" + deleted;
    } finally {
      CameraImportGate.lock.unlock();
    }
  }

  private static PilotSession createSession(Context context) throws IOException {
    String runId = UUID.randomUUID().toString();
    String databaseName = DATABASE_PREFIX + runId + ".db";
    String folderName = FOLDER_PREFIX + runId;
    String prefsName = RUN_PREFS_PREFIX + runId;
    SharedPreferences prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
    if (!prefs
        .edit()
        .clear()
        .putString(RUN_ID_KEY, runId)
        .putString("database_name", databaseName)
        .putString("folder_name", folderName)
        .commit()) {
      context.deleteSharedPreferences(prefsName);
      throw new IOException("Could not create pilot preferences");
    }
    SharedPreferences index = context.getSharedPreferences(INDEX_PREFS, Context.MODE_PRIVATE);
    if (!index.edit().putString(RUN_ID_KEY, runId).commit()) {
      context.deleteSharedPreferences(prefsName);
      throw new IOException("Could not register pilot run");
    }
    return new PilotSession(runId, databaseName, folderName, prefsName, prefs);
  }

  private static PilotSession loadCurrentSession(Context context) throws IOException {
    String runId =
        context.getSharedPreferences(INDEX_PREFS, Context.MODE_PRIVATE).getString(RUN_ID_KEY, null);
    if (runId == null) return null;
    if (!validRunId(runId)) throw new IOException("Invalid pilot run identifier");
    String databaseName = DATABASE_PREFIX + runId + ".db";
    String folderName = FOLDER_PREFIX + runId;
    String prefsName = RUN_PREFS_PREFIX + runId;
    SharedPreferences prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
    if (!runId.equals(prefs.getString(RUN_ID_KEY, null))
        || !databaseName.equals(prefs.getString("database_name", null))
        || !folderName.equals(prefs.getString("folder_name", null))) {
      throw new IOException("Pilot run ownership metadata does not match its UUID");
    }
    return new PilotSession(runId, databaseName, folderName, prefsName, prefs);
  }

  private static void saveWifiResults(
      PilotSession session,
      boolean stable,
      List<CameraImportFile> sources,
      List<String> sourceShas,
      List<String> uris)
      throws IOException {
    if (uris.size() != MAX_FILES || sources.size() != MAX_FILES || sourceShas.size() != MAX_FILES) {
      throw new IOException("Invalid Wi-Fi pilot result set");
    }
    SharedPreferences.Editor editor =
        session
            .prefs
            .edit()
            .putBoolean("wifi_complete", true)
            .putBoolean("wifi_stable", stable)
            .putInt("wifi_count", uris.size());
    for (int i = 0; i < uris.size(); i++) {
      if (!validSha(sourceShas.get(i))) throw new IOException("Invalid Wi-Fi pilot fingerprint");
      editor.putString("wifi_path_" + i, sources.get(i).relativePath);
      editor.putLong("wifi_bytes_" + i, sources.get(i).bytes);
      editor.putString("wifi_sha_" + i, sourceShas.get(i));
      editor.putString("wifi_uri_" + i, uris.get(i));
    }
    if (!editor.commit()) throw new IOException("Could not save Wi-Fi pilot results");
  }

  private static List<WifiProof> loadWifiProof(PilotSession session) throws IOException {
    if (!session.prefs.getBoolean("wifi_complete", false)) return new ArrayList<>();
    int count = session.prefs.getInt("wifi_count", -1);
    if (count != MAX_FILES)
      throw new IOException("Complete Wi-Fi pilot proof is missing selected files");
    List<WifiProof> proof = new ArrayList<>(count);
    Set<String> paths = new HashSet<>();
    for (int i = 0; i < count; i++) {
      String path = session.prefs.getString("wifi_path_" + i, null);
      long bytes = session.prefs.getLong("wifi_bytes_" + i, 0);
      String sha = session.prefs.getString("wifi_sha_" + i, null);
      String value = session.prefs.getString("wifi_uri_" + i, null);
      if (path == null
          || bytes <= 0
          || bytes > MAX_PILOT_BYTES
          || !validSha(sha)
          || value == null) {
        throw new IOException("Wi-Fi pilot content proof is incomplete");
      }
      try {
        new CameraImportFile("pilot-proof", path, bytes, "", 0);
      } catch (IllegalArgumentException invalidPath) {
        throw new IOException("Wi-Fi pilot path proof is invalid", invalidPath);
      }
      if (!paths.add(path.toUpperCase(Locale.ROOT)))
        throw new IOException("Duplicate Wi-Fi pilot path proof");
      Uri uri = Uri.parse(value);
      if (!"content".equals(uri.getScheme())
          || !"media".equals(uri.getAuthority())
          || uri.getPath() == null
          || !uri.getPath().matches("/(?:external|external_primary)/images/media/[0-9]+")) {
        throw new IOException("Wi-Fi pilot URI is invalid");
      }
      proof.add(new WifiProof(path, bytes, sha, value));
    }
    return proof;
  }

  private static boolean validSha(String value) {
    return value != null && value.matches("[0-9a-f]{64}");
  }

  private static int cleanupSession(Context context, PilotSession session) throws IOException {
    validateSession(session);
    String currentRun =
        context.getSharedPreferences(INDEX_PREFS, Context.MODE_PRIVATE).getString(RUN_ID_KEY, null);
    if (!session.runId.equals(currentRun))
      throw new IOException("Refusing to clean a non-current pilot run");
    int deleted = cleanOwnedFolder(context, CameraPhotoPublisher.folder(session.folderName, false));
    deleted += cleanOwnedFolder(context, CameraPhotoPublisher.folder(session.folderName, true));

    java.io.File database = context.getDatabasePath(session.databaseName);
    if (database.exists() && !context.deleteDatabase(session.databaseName)) {
      throw new IOException("Could not delete isolated pilot database");
    }
    if (!context.deleteSharedPreferences(session.prefsName)) {
      throw new IOException("Could not delete isolated pilot preferences");
    }
    SharedPreferences index = context.getSharedPreferences(INDEX_PREFS, Context.MODE_PRIVATE);
    if (!session.runId.equals(index.getString(RUN_ID_KEY, null))
        || !index.edit().clear().commit()
        || !context.deleteSharedPreferences(INDEX_PREFS)) {
      throw new IOException("Could not clear isolated pilot index");
    }
    return deleted;
  }

  private static void validateSession(PilotSession session) throws IOException {
    if (!validRunId(session.runId)
        || !session.databaseName.equals(DATABASE_PREFIX + session.runId + ".db")
        || !session.folderName.equals(FOLDER_PREFIX + session.runId)
        || !session.prefsName.equals(RUN_PREFS_PREFIX + session.runId)) {
      throw new IOException("Refusing cleanup without a validated pilot UUID prefix");
    }
    if (!session.runId.equals(session.prefs.getString(RUN_ID_KEY, null))
        || !session.databaseName.equals(session.prefs.getString("database_name", null))
        || !session.folderName.equals(session.prefs.getString("folder_name", null))) {
      throw new IOException("Refusing cleanup with mismatched pilot preferences");
    }
  }

  private static int cleanOwnedFolder(Context context, String relativeFolder) throws IOException {
    ContentResolver resolver = context.getContentResolver();
    Bundle args = new Bundle();
    args.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
    args.putString(
        ContentResolver.QUERY_ARG_SQL_SELECTION,
        MediaStore.MediaColumns.RELATIVE_PATH
            + "=? AND "
            + MediaStore.MediaColumns.OWNER_PACKAGE_NAME
            + "=?");
    args.putStringArray(
        ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
        new String[] {relativeFolder, context.getPackageName()});
    List<Uri> uris = new ArrayList<>();
    try (Cursor cursor =
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            new String[] {MediaStore.MediaColumns._ID},
            args,
            null)) {
      if (cursor == null) throw new IOException("Could not query owned pilot media");
      while (cursor.moveToNext()) {
        uris.add(
            Uri.withAppendedPath(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Long.toString(cursor.getLong(0))));
      }
    }
    int deleted = 0;
    for (Uri uri : uris) {
      deleteOwnedUri(context, uri, relativeFolder);
      deleted++;
    }
    return deleted;
  }

  private static void deleteOwnedUri(Context context, Uri uri, String relativeFolder)
      throws IOException {
    if (!"content".equals(uri.getScheme())
        || !"media".equals(uri.getAuthority())
        || !uri.getPath().matches("/(?:external|external_primary)/images/media/[0-9]+")) {
      throw new IOException("Refusing unexpected pilot media URI");
    }
    Bundle args = new Bundle();
    args.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
    try (Cursor cursor = context.getContentResolver().query(uri, OWNED_MEDIA_FIELDS, args, null)) {
      if (cursor == null || cursor.getCount() != 1 || !cursor.moveToFirst()) {
        throw new IOException("Could not recheck pilot media before deletion");
      }
      if (!context.getPackageName().equals(cursor.getString(1))
          || !relativeFolder.equals(cursor.getString(2))) {
        throw new IOException("Pilot media owner or folder changed; refusing deletion");
      }
    }
    if (context.getContentResolver().delete(uri, null, null) != 1) {
      throw new IOException("Could not delete owned pilot media");
    }
  }

  private static List<Ep7WifiClient.Entry> selectWifi(List<Ep7WifiClient.Entry> photos)
      throws IOException {
    List<Ep7WifiClient.Entry> jpegs = new ArrayList<>();
    List<Ep7WifiClient.Entry> raws = new ArrayList<>();
    for (Ep7WifiClient.Entry entry : photos) {
      if (isJpeg(entry.name())) jpegs.add(entry);
      else if (isRaw(entry.name())) raws.add(entry);
    }
    Comparator<Ep7WifiClient.Entry> order = Comparator.comparing(entry -> entry.path);
    jpegs.sort(order);
    raws.sort(order);
    if (jpegs.size() < JPEG_COUNT || raws.size() < RAW_COUNT) {
      throw new IOException("Camera needs two JPEG and two RAW pilot photos");
    }
    List<Ep7WifiClient.Entry> selected = new ArrayList<>(MAX_FILES);
    selected.addAll(jpegs.subList(0, JPEG_COUNT));
    selected.addAll(raws.subList(0, RAW_COUNT));
    if (totalWifiBytes(selected) > MAX_PILOT_BYTES)
      throw new IOException("Wi-Fi pilot exceeds 256 MiB");
    return selected;
  }

  private static List<CameraDocumentSource.Entry> selectSd(List<CameraDocumentSource.Entry> photos)
      throws IOException {
    List<CameraDocumentSource.Entry> ordered = new ArrayList<>(photos);
    ordered.sort(Comparator.comparing(entry -> entry.file.relativePath));
    List<CameraDocumentSource.Entry> selected = new ArrayList<>(MAX_FILES);
    Set<String> uris = new HashSet<>();
    long[] bytes = {0};
    addSdType(ordered, selected, uris, bytes, true, JPEG_COUNT);
    addSdType(ordered, selected, uris, bytes, false, RAW_COUNT);
    for (CameraDocumentSource.Entry entry : ordered) {
      if (selected.size() == MAX_FILES) break;
      addSd(entry, selected, uris, bytes);
    }
    if (selected.isEmpty()) throw new IOException("No SD photos are available for the pilot");
    return selected;
  }

  private static List<CameraDocumentSource.Entry> selectSdForWifi(
      List<CameraDocumentSource.Entry> photos, List<WifiProof> proof) throws IOException {
    if (proof.size() != MAX_FILES)
      throw new IOException("Wi-Fi cross-route proof must contain four files");
    List<CameraDocumentSource.Entry> selected = new ArrayList<>(proof.size());
    Set<String> selectedUris = new HashSet<>();
    for (WifiProof expected : proof) {
      List<CameraDocumentSource.Entry> matches = new ArrayList<>();
      for (CameraDocumentSource.Entry entry : photos) {
        if (entry.file.bytes == expected.bytes
            && entry.file.relativePath.equalsIgnoreCase(expected.relativePath)) {
          matches.add(entry);
        }
      }
      if (matches.isEmpty())
        throw new IOException("A Wi-Fi pilot file is missing from the selected SD tree");
      if (matches.size() != 1 || !selectedUris.add(matches.get(0).uri.toString())) {
        throw new IOException("A Wi-Fi pilot file is ambiguous in the selected SD tree");
      }
      selected.add(matches.get(0));
    }
    if (totalSdBytes(selected) > MAX_PILOT_BYTES)
      throw new IOException("SD cross-route pilot exceeds 256 MiB");
    return selected;
  }

  private static void addSdType(
      List<CameraDocumentSource.Entry> entries,
      List<CameraDocumentSource.Entry> selected,
      Set<String> uris,
      long[] bytes,
      boolean jpeg,
      int targetCount) {
    int count = 0;
    for (CameraDocumentSource.Entry entry : entries) {
      if (count >= targetCount || selected.size() >= MAX_FILES) break;
      if (jpeg ? !isJpeg(entry.file.name) : !isRaw(entry.file.name)) continue;
      if (addSd(entry, selected, uris, bytes)) count++;
    }
  }

  private static boolean addSd(
      CameraDocumentSource.Entry entry,
      List<CameraDocumentSource.Entry> selected,
      Set<String> uris,
      long[] bytes) {
    String key = entry.uri.toString();
    if (uris.contains(key)
        || selected.size() >= MAX_FILES
        || entry.file.bytes > MAX_PILOT_BYTES - bytes[0]) return false;
    uris.add(key);
    selected.add(entry);
    bytes[0] += entry.file.bytes;
    return true;
  }

  private static boolean isJpeg(String name) {
    String upper = name.toUpperCase(Locale.ROOT);
    return upper.endsWith(".JPG") || upper.endsWith(".JPEG");
  }

  private static boolean isRaw(String name) {
    String upper = name.toUpperCase(Locale.ROOT);
    return upper.endsWith(".DNG") || upper.endsWith(".RW2") || upper.endsWith(".ORF");
  }

  private static CameraImportFile wifiSource(String sourceId, Ep7WifiClient.Entry entry) {
    return new CameraImportFile(
        sourceId, entry.path.substring(1), entry.bytes, entry.date + ":" + entry.time, 0);
  }

  private static long totalWifiBytes(List<Ep7WifiClient.Entry> entries) throws IOException {
    long bytes = 0;
    for (Ep7WifiClient.Entry entry : entries) {
      if (entry.bytes <= 0 || entry.bytes > MAX_PILOT_BYTES)
        throw new IOException("Unsupported Wi-Fi pilot file size");
      bytes = Math.addExact(bytes, entry.bytes);
    }
    return bytes;
  }

  private static long totalSdBytes(List<CameraDocumentSource.Entry> entries) throws IOException {
    long bytes = 0;
    for (CameraDocumentSource.Entry entry : entries) bytes = Math.addExact(bytes, entry.file.bytes);
    if (bytes > MAX_PILOT_BYTES) throw new IOException("SD pilot exceeds 256 MiB");
    return bytes;
  }

  private static void requireNetwork(
      Context context, ConnectivityManager manager, Network network, String expectedIdentity)
      throws IOException {
    if (manager.getNetworkCapabilities(network) == null)
      throw new IOException("Camera Wi-Fi disconnected");
    if (expectedIdentity != null
        && !expectedIdentity.equals(CameraSourceIdentity.wifi(context, network))) {
      throw new IOException("Camera Wi-Fi identity changed during pilot");
    }
  }

  private static void copyWifi(
      Context context,
      ConnectivityManager manager,
      Network network,
      String expectedIdentity,
      Ep7WifiClient client,
      Ep7WifiClient.Entry entry,
      java.io.OutputStream output)
      throws IOException {
    requireNetwork(context, manager, network, expectedIdentity);
    client.copyOriginal(entry, output);
    requireNetwork(context, manager, network, expectedIdentity);
  }

  private static double elapsed(long start) {
    return Math.max(1, System.nanoTime() - start) / 1_000_000_000.0;
  }

  private static String metric(double value) {
    return String.format(Locale.ROOT, "%.3f", value);
  }

  private static boolean validRunId(String value) {
    if (value == null || !value.matches("[0-9a-fA-F-]{36}")) return false;
    try {
      return UUID.fromString(value).toString().equalsIgnoreCase(value);
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  private static final class PilotSession {
    final String runId;
    final String databaseName;
    final String folderName;
    final String prefsName;
    final SharedPreferences prefs;

    PilotSession(
        String runId,
        String databaseName,
        String folderName,
        String prefsName,
        SharedPreferences prefs) {
      this.runId = runId;
      this.databaseName = databaseName;
      this.folderName = folderName;
      this.prefsName = prefsName;
      this.prefs = prefs;
    }
  }

  private static final class WifiProof {
    final String relativePath;
    final long bytes;
    final String sourceSha;
    final String uri;

    WifiProof(String relativePath, long bytes, String sourceSha, String uri) {
      this.relativePath = relativePath;
      this.bytes = bytes;
      this.sourceSha = sourceSha;
      this.uri = uri;
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
