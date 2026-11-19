package jp.nano.rawgallery;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises the bounded transfer loop and durable cursor without accessing media or the network. */
final class E2BatchChecks {
  static String run(Context context) throws Exception {
    String name = "e2-batch-check-" + UUID.randomUUID();
    SharedPreferences preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE);
    try {
      E2AutoSync.Candidate first = candidate(1, 11);
      E2AutoSync.Candidate second = candidate(2, 12);
      E2AutoSync.Candidate third = candidate(3, 13);
      List<E2AutoSync.Candidate> candidates = List.of(first, second, third);
      int total = candidates.size();
      String identity = E2AutoSync.batchIdentity("fixture", candidates);
      E2AutoSync.Batch batch = new E2AutoSync.Batch(preferences, identity, total);
      AtomicBoolean expired = new AtomicBoolean();
      AtomicInteger permits = new AtomicInteger(1);
      List<E2AutoSync.Candidate> attempted = Collections.synchronizedList(new ArrayList<>());
      E2AutoSync.transferBatch(preferences, candidates, batch, () -> false,
          () -> !expired.get() && permits.getAndDecrement() > 0, candidate -> {
        attempted.add(candidate);
        // The budget expires after admission but before this transfer completes.
        expired.set(true);
        return new E2AutoSync.Transfer(true, null);
      });
      check(attempted.equals(List.of(first)) && batch.ready == 1 && batch.failed == 0,
          "The first batch must finish its admitted transfer and leave the tail");
      check(batch.shouldContinue(total, 0), "Progress should request a continuation");
      SharedPreferences.Editor saved = preferences.edit();
      batch.save(saved);
      check(saved.commit(), "Could not persist the verified prefix");

      String drift = E2AutoSync.batchIdentity("fixture", List.of(candidate(1, 21), second, third));
      check(new E2AutoSync.Batch(preferences, drift, total).processed() == 0,
          "A changed MediaStore generation must invalidate the cursor");
      String otherDestination = E2AutoSync.batchIdentity("other", candidates);
      check(new E2AutoSync.Batch(preferences, otherDestination, total).processed() == 0,
          "Another destination must start from the beginning");

      E2AutoSync.Batch resumed = new E2AutoSync.Batch(preferences, identity, total);
      E2AutoSync.transferBatch(preferences, candidates, resumed, () -> false, () -> true, candidate -> {
        attempted.add(candidate);
        return new E2AutoSync.Transfer(true, null);
      });
      check(attempted.equals(candidates) && resumed.ready == total && resumed.failed == 0,
          "The second batch must continue from the durable prefix");
      check(!resumed.shouldContinue(total, 1), "A complete sweep needs no continuation");

      E2AutoSync.Batch failed = new E2AutoSync.Batch(preferences, identity, total);
      E2AutoSync.transferBatch(preferences, candidates, failed, () -> false,
          () -> false, candidate -> new E2AutoSync.Transfer(false, null));
      check(failed.ready == 1 && failed.failed == 1 && !failed.shouldContinue(total, 1),
          "Failure alone must not trigger a rapid retry");
      E2AutoSync.Batch warmedUp = new E2AutoSync.Batch(preferences, identity, total);
      AtomicInteger admittedAfterDeadline = new AtomicInteger();
      E2AutoSync.transferBatch(preferences, candidates, warmedUp, () -> false, () -> false, candidate -> {
        check(candidate.equals(second), "The expired batch must still resume at the next candidate");
        admittedAfterDeadline.incrementAndGet();
        return new E2AutoSync.Transfer(true, null);
      });
      check(admittedAfterDeadline.get() == 1 && warmedUp.ready == 2 && warmedUp.shouldContinue(total, 1),
          "An expired startup budget must still admit one candidate");
      E2AutoSync.transferBatch(preferences, candidates, warmedUp, () -> true, () -> false, candidate -> {
        throw new AssertionError("Cancellation must prevent the first admission");
      });
      check(!warmedUp.shouldContinue(total, 2), "No progress must not trigger a retry");

      SharedPreferences.Editor cleared = preferences.edit();
      E2AutoSync.clearBatch(cleared);
      check(cleared.commit() && new E2AutoSync.Batch(preferences, identity, total).processed() == 0,
          "Reset must discard the old cursor");
      fallback(preferences);
      return "PASS e2-batches: drain, resume, metadata/content drift, warm-up, zero progress, reset";
    } finally {
      context.deleteSharedPreferences(name);
    }
  }

  private static void fallback(SharedPreferences preferences) throws Exception {
    E2AutoSync.Candidate fallback = candidate(1, 0);
    List<E2AutoSync.Candidate> photos = List.of(fallback, candidate(2, 11), candidate(3, 0));
    String identity = E2AutoSync.batchIdentity("fixture", photos);
    byte[] original = new byte[250];
    byte[] changed = original.clone();
    changed[0] = 7;
    String originalHash = E2Archive.readHash(new ByteArrayInputStream(original), null, original.length);
    E2AutoSync.Batch prior = new E2AutoSync.Batch(preferences, identity, photos.size());
    E2AutoSync.transferBatch(preferences, photos, prior, () -> false, () -> false, candidate ->
        new E2AutoSync.Transfer(true, originalHash));
    SharedPreferences.Editor editor = preferences.edit();
    prior.save(editor);
    check(editor.commit(), "Could not persist the fallback content identity");

    E2AutoSync.Batch same = new E2AutoSync.Batch(preferences, identity, photos.size());
    same.validateFallback(preferences, photos, candidate -> {
      check(candidate.equals(fallback), "Only the processed fallback prefix should open originals");
      return new ByteArrayInputStream(original);
    }, () -> false);
    check(same.ready == 1, "Identical fallback bytes should keep the cursor");
    E2AutoSync.Batch drift = new E2AutoSync.Batch(preferences, identity, photos.size());
    drift.validateFallback(preferences, photos, candidate -> new ByteArrayInputStream(changed), () -> false);
    check(drift.processed() == 0, "Changed bytes with identical metadata must invalidate the prefix");
    E2AutoSync.Batch unavailable = new E2AutoSync.Batch(preferences, identity, photos.size());
    unavailable.validateFallback(preferences, photos, candidate -> {
      throw new IOException("Synthetic unavailable original");
    }, () -> false);
    check(unavailable.processed() == 0, "Unreadable bytes cannot match an acknowledged fallback");
    E2AutoSync.transferBatch(preferences, photos, unavailable, () -> false, () -> false, candidate ->
        new E2AutoSync.Transfer(true, null));
    check(unavailable.ready == 0 && unavailable.failed == 1,
        "Missing fallback hash cannot produce a ready result");
  }

  private static E2AutoSync.Candidate candidate(long id, long generation) {
    Uri uri = Uri.parse("content://media/external/file/" + id);
    PhotoRepository.Photo photo = new PhotoRepository.Photo(uri, "fixture-" + id + ".jpg",
        "Pictures/Fixture/", 250, 100, 0);
    return new E2AutoSync.Candidate(photo, "external_primary", "fixture-version", generation);
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
