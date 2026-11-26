package jp.nano.rawgallery;

import android.content.Context;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Owns a ranking namespace for a disposable test activity. Use from the test thread. */
final class RankingFixture implements AutoCloseable {
  private final Context context;
  private final String namespace = "rankings-ui-fixture-" + UUID.randomUUID();
  final RankingStore store;

  RankingFixture(Context context) {
    this.context = context.getApplicationContext();
    store = new RankingStore(this.context.getSharedPreferences(namespace, Context.MODE_PRIVATE));
  }

  PhotoRanking startBurst(List<String> ids, String journal) throws IOException {
    // Journal decisions are positional. Replay uses the store's canonical member order.
    return new PhotoRanking(store.awaitReady().burst(ids).ids(), journal);
  }

  void save(PhotoRanking ranking) throws Exception {
    RankingStore.Burst previous = store.awaitReady().burst(ranking.ids);
    RankingStore.WriteResult saved =
        store.submitBurst(previous, () -> ranking).toCompletableFuture().get(8, TimeUnit.SECONDS);
    TagDetailChecks.require(
        saved.outcome() == RankingStore.Outcome.SAVED
            || saved.outcome() == RankingStore.Outcome.UNCHANGED,
        "Persist isolated burst ranking: " + saved.outcome());
  }

  @Override
  public void close() throws Exception {
    RankingPersistenceWaitChecks.drainRankingWriter(store);
    TagDetailChecks.require(
        context.deleteSharedPreferences(namespace), "Delete drained isolated rankings");
  }
}
