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
    return new PhotoRanking(sorted, preferences.getString(key(sorted), ""));
  }

  /** Caller must advance its displayed state only when synchronous commit succeeds. */
  boolean save(PhotoRanking next) throws IOException {
    return preferences.edit().putString(key(next.ids), next.journal).commit();
  }
}
