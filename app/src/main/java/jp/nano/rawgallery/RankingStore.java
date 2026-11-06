package jp.nano.rawgallery;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.util.*;

/**
 * Persists comparison journals independently of screen and share selection state.
 * Contract: docs/current-design.md P2.2 and the extraction contract.
 * Keeps the existing rankings preference file, v1 keys and journal encoding.
 */
final class RankingStore {
  private final SharedPreferences preferences;

  RankingStore(Context context) {
    this(context.getSharedPreferences("rankings", 0));
  }

  RankingStore(SharedPreferences preferences) {
    this.preferences = preferences;
  }

  private static String key(List<String> ids) throws IOException {
    List<String> sorted = new ArrayList<>(ids);
    Collections.sort(sorted);
    return "v1-" + PhotoRepository.digest(String.join("|", sorted));
  }

  /** Same member set resumes the same journal; corrupt journals are not overwritten. */
  PhotoRanking load(List<String> ids) throws IOException {
    List<String> sorted = new ArrayList<>(ids);
    Collections.sort(sorted);
    synchronized (preferences) {
      return new PhotoRanking(sorted, preferences.getString(key(sorted), ""));
    }
  }

  /** Caller must advance its displayed state only when synchronous commit succeeds. */
  boolean save(PhotoRanking next) throws IOException {
    synchronized (preferences) {
      return preferences.edit().putString(key(next.ids), next.journal).commit();
    }
  }

  /** Commit the batch only against the journal captured at confirmation. */
  boolean saveBulk(PhotoRanking original, PhotoRanking next) throws IOException {
    if (!original.ids.equals(next.ids)) throw new IllegalArgumentException("Different ranking members");
    String key = key(original.ids);
    synchronized (preferences) {
      String previous = preferences.getString(key, null);
      if (!original.journal.equals(previous == null ? "" : previous)) return false;
      if (next.journal.equals(original.journal)) return true;
      boolean committed = false;
      try {
        committed = preferences.edit().putString(key, next.journal).commit();
        return committed;
      } finally {
        if (!committed) {
          // SharedPreferences updates its in-memory value before attempting the disk write.
          SharedPreferences.Editor rollback = preferences.edit();
          if (previous == null) rollback.remove(key);
          else rollback.putString(key, previous);
          rollback.commit();
        }
      }
    }
  }
}
