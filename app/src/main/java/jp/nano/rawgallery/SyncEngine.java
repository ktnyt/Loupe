package jp.nano.rawgallery;

import android.content.*;
import android.database.Cursor;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

final class SyncEngine {
  static final AtomicBoolean RUNNING = new AtomicBoolean();

  static boolean managed(Context c, PhotoRepository.Photo p, boolean write) {
    return "content".equals(p.uri.getScheme())
        && "media".equals(p.uri.getAuthority())
        && (!write || android.os.Environment.isExternalStorageManager());
  }

  static List<PhotoRepository.Photo> candidates(Context c) throws Exception {
    List<PhotoRepository.Photo> list = new ArrayList<>();
    try (ArchiveStore store = new ArchiveStore(c)) {
      for (PhotoRepository.Photo p : new PhotoRepository(c).scan("すべて")) {
        if (Thread.currentThread().isInterrupted()) break;
        if (!managed(c, p, false)) continue;
        try {
          ArchiveStore.RawMetadata metadata = store.metadata(p);
          if (p.isJpeg() || SyncPolicy.supported(metadata.model))
            list.add(
                new PhotoRepository.Photo(
                    p.uri,
                    p.name,
                    p.folder,
                    p.size,
                    p.modified,
                    metadata.taken,
                    p.added,
                    p.isJpeg() && metadata.taken <= 0 && p.added <= 0
                        ? PhotoRepository.creationTime(c, p)
                        : 0));
        } catch (Exception e) {
          /* Unsupported or unreadable RAWs are never cleanup candidates. */
        }
      }
    }
    return list;
  }

  static String summary(Context c) throws Exception {
    long bytes = 0, eligibleBytes = 0;
    int eligible = 0;
    List<PhotoRepository.Photo> list = candidates(c);
    long now = System.currentTimeMillis();
    int days = c.getSharedPreferences("settings", 0).getInt("retentionDays", 7);
    for (PhotoRepository.Photo p : list) {
      bytes += p.size;
      if (SyncPolicy.eligible(
          p.taken, p.added, p.created, p.isJpeg(), now, days, true, managed(c, p, true), true)) {
        eligible++;
        eligibleBytes += p.size;
      }
    }
    return "対象："
        + list.size()
        + "枚（"
        + String.format(Locale.JAPAN, "%.2f GiB", bytes / 1073741824.0)
        + "）\n"
        + "基準日から"
        + days
        + "日以上、または日付不明のJPEG："
        + eligible
        + "枚（"
        + String.format(Locale.JAPAN, "%.2f GiB", eligibleBytes / 1073741824.0)
        + "）\n"
        + "JPEGは撮影日→追加日→取得できる作成日を使います。すべて不明なら復元照合・プレビュー保存後すぐ整理対象になります。RAWは撮影日不明なら残します。";
  }

  static void status(Context c, JobState.Kind kind, String message) {
    long at=System.currentTimeMillis();
    JobState.write(c.getSharedPreferences("settings", 0).edit(),"sync",kind,at)
        .putString("syncStatus", message).putLong("syncStatusAt", at).apply();
  }

  static JobState snapshot(Context c) {
    return JobState.read(c.getSharedPreferences("settings",0),"sync",RUNNING.get(),false);
  }

  static void run(Context c, String cookies, String agent) {
    try {
      runBatch(c, cookies, agent, candidates(c), true);
    } catch (Exception e) {
      status(c, JobState.Kind.FAILED, "対象フォルダを読み取れませんでした。アクセス許可を確認してください。");
    }
  }

  static void runBatch(
      Context c,
      String cookies,
      String agent,
      List<PhotoRepository.Photo> batch,
      boolean allowUploads) {
    if (!RUNNING.compareAndSet(false, true)) return;
    try (ArchiveStore store = new ArchiveStore(c)) {
      if (cookies == null || cookies.isBlank()) throw new CloudVerifier.AuthException();
      CloudVerifier cloud = new CloudVerifier(cookies, agent);
      long deadline = System.nanoTime() + 240_000_000_000L;
      int processed = 0, deferred = 0;
      for (PhotoRepository.Photo p : batch) {
        SharedPreferences settings = c.getSharedPreferences("settings", 0);
        if (!settings.getBoolean("syncEnabled", false)
            || Thread.currentThread().isInterrupted()
            || System.nanoTime() > deadline
            || processed >= 20) break;
        JSONObject old = store.get(ArchiveStore.key(p));
        if (old != null && old.optLong("retryAfter", 0) > System.currentTimeMillis()) {
          deferred++;
          continue;
        }
        try {
          boolean verified = old != null && old.has("sha");
          boolean expired =
              SyncPolicy.eligible(
                  p.taken,
                  p.added,
                  p.created,
                  p.isJpeg(),
                  System.currentTimeMillis(),
                  settings.getInt("retentionDays", 7),
                  verified,
                  managed(c, p, true),
                  verified);
          if (verified && !(settings.getBoolean("cleanupEnabled", false) && expired)) continue;
          status(c, JobState.Kind.RUNNING, p.name + "\n同期と復元を確認しています…");
          if (old != null && "pending".equals(old.optString("state")))
            cloud.verify(c, p, text -> status(c, JobState.Kind.RUNNING, text));
          else if (!verified && allowUploads) cloud.uploadAndVerify(c, p, text -> status(c, JobState.Kind.RUNNING, text));
          else cloud.verify(c, p, text -> status(c, JobState.Kind.RUNNING, text));
          processed++;
          if (cloud.lastVerified == null) {
            JSONObject pending = store.get(ArchiveStore.key(p));
            if (pending == null) pending = ArchiveStore.base(p);
            pending.put("retryAfter", System.currentTimeMillis() + 3600000L);
            store.put(pending, "pending");
            deferred++;
            status(c, JobState.Kind.PARTIAL, p.name + "：保存結果が未確認です。自動再送せず、次回も照合だけ行います。");
            continue;
          }
          store.verified(p, cloud.lastVerified);
          JSONObject record = store.get(ArchiveStore.key(p));
          if (settings.getBoolean("syncEnabled", false)
              && settings.getBoolean("cleanupEnabled", false)
              && SyncPolicy.eligible(
                  p.taken,
                  p.added,
                  p.created,
                  p.isJpeg(),
                  System.currentTimeMillis(),
                  settings.getInt("retentionDays", 7),
                  true,
                  managed(c, p, true),
                  true)) {
            // Recheck both file identity metadata and bytes immediately before the explicit
            // media-item delete.
            store.preview(record.getString("key"));
            if (!unchanged(c, p) || !cloud.lastVerified.sha.equals(CloudVerifier.localSha(c, p))) {
              status(c, JobState.Kind.PAUSED, p.name + "：端末の原本が変わったため整理を見送りました。");
              continue;
            }
            if (Thread.currentThread().isInterrupted()
                || !settings.getBoolean("cleanupEnabled", false)
                || !settings.getBoolean("syncEnabled", false)) break;
            store.put(record, "releasing");
            if (c.getContentResolver().delete(p.uri, null, null) != 1)
              throw new java.io.IOException("Delete refused");
            store.put(record, "released");
          }
        } catch (CloudVerifier.AuthException e) {
          throw e;
        } catch (Exception e) {
          if (Thread.currentThread().isInterrupted()) break;
          JSONObject failed = store.get(ArchiveStore.key(p));
          if (failed == null) failed = ArchiveStore.base(p);
          String state = failed.optString("state", "error");
          failed.put("retryAfter", System.currentTimeMillis() + 3600000L);
          store.put(failed, state);
          deferred++;
          processed++;
        }
      }
      status(
          c, deferred > 0 ? JobState.Kind.PARTIAL : JobState.Kind.WAITING, "今回の同期処理を終了しました（確認した原本：" + processed + "枚、保留：" + deferred + "枚）。未完了の原本は次の定期処理で確認します。");
    } catch (CloudVerifier.AuthException e) {
      c.getSharedPreferences("settings", 0).edit().putBoolean("syncEnabled", false).apply();
      SyncJob.schedule(c);
      status(c, JobState.Kind.AUTH_REQUIRED, "Amazonへの再ログインが必要なため、自動同期と整理を停止しました。");
    } catch (Exception e) {
      status(c, JobState.Kind.FAILED, "同期を中断しました。未確認の原本は整理しません。通信・アクセス許可・空き容量を確認してください。");
    } finally {
      RUNNING.set(false);
    }
  }

  static boolean unchanged(Context c, PhotoRepository.Photo p) {
    String[] fields = {
      android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
      android.provider.MediaStore.MediaColumns.SIZE,
      android.provider.MediaStore.MediaColumns.DATE_MODIFIED
    };
    try (Cursor cursor = c.getContentResolver().query(p.uri, fields, null, null, null)) {
      return cursor != null
          && cursor.moveToFirst()
          && p.name.equals(cursor.getString(0))
          && p.size == cursor.getLong(1)
          && p.modified == cursor.getLong(2);
    } catch (Exception e) {
      return false;
    }
  }
}
