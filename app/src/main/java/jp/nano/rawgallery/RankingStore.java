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

  private static String singleBestKey(String photoKey) throws IOException {
    return "single-best-v1-" + PhotoRepository.digest(photoKey);
  }

  private static boolean decodeSingleBest(String value) throws IOException {
    if (value == null || value.equals("0")) return false;
    if (value.equals("1")) return true;
    throw new IOException("Corrupt singleton best value");
  }

  /** Reads the independent singleton mark; an absent value means not marked. */
  boolean singleBest(String photoKey) throws IOException {
    String key = singleBestKey(photoKey);
    synchronized (preferences) {
      return decodeSingleBest(preferences.getString(key, null));
    }
  }

  /** Writes only if the saved mark still matches the value the caller observed. */
  boolean saveSingleBest(String photoKey, boolean expected, boolean next) throws IOException {
    String key = singleBestKey(photoKey);
    synchronized (preferences) {
      String previous = preferences.getString(key, null);
      if (decodeSingleBest(previous) != expected) return false;
      if (expected == next) return true;

      boolean committed;
      try {
        committed = preferences.edit().putString(key, next ? "1" : "0").commit();
      } catch (RuntimeException | Error failure) {
        rollbackSingleBest(key, previous, failure);
        throw failure;
      }
      if (!committed) rollbackSingleBest(key, previous, null);
      return committed;
    }
  }

  private void rollbackSingleBest(String key, String previous, Throwable failure) throws IOException {
    try {
      SharedPreferences.Editor rollback = preferences.edit();
      if (previous == null) rollback.remove(key);
      else rollback.putString(key, previous);
      rollback.commit();
    } catch (RuntimeException | Error rollbackFailure) {
      if (failure != null) failure.addSuppressed(rollbackFailure);
      else throw new IOException("Could not roll back singleton best value", rollbackFailure);
    }
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
