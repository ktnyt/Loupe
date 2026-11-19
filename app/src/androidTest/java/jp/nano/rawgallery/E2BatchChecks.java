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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/** Exercises the bounded transfer loop and durable cursor without accessing media or the network. */
final class E2BatchChecks {
  private static final LongSupplier ENOUGH_SPACE = () -> Long.MAX_VALUE;

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
      CountDownLatch expired = new CountDownLatch(1);
      List<E2AutoSync.Candidate> attempted = Collections.synchronizedList(new ArrayList<>());
      E2AutoSync.transferBatch(preferences, candidates, batch, () -> false, () -> {
        // Wait for the running transfer to exhaust the admission budget.
        await(expired);
        return false;
      }, ENOUGH_SPACE, candidate -> {
        attempted.add(candidate);
        expired.countDown();
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
      E2AutoSync.transferBatch(preferences, candidates, resumed, () -> false, () -> true, ENOUGH_SPACE, candidate -> {
        attempted.add(candidate);
        return new E2AutoSync.Transfer(true, null);
      });
      check(attempted.size() == total && attempted.containsAll(candidates)
          && resumed.ready == total && resumed.failed == 0,
          "The second batch must continue from the durable prefix");
      check(!resumed.shouldContinue(total, 1), "A complete sweep needs no continuation");

      E2AutoSync.Batch failed = new E2AutoSync.Batch(preferences, identity, total);
      E2AutoSync.transferBatch(preferences, candidates, failed, () -> false,
          () -> false, ENOUGH_SPACE, candidate -> new E2AutoSync.Transfer(false, null));
      check(failed.ready == 1 && failed.failed == 1 && !failed.shouldContinue(total, 1),
          "Failure alone must not trigger a rapid retry");
      E2AutoSync.Batch warmedUp = new E2AutoSync.Batch(preferences, identity, total);
      AtomicInteger admittedAfterDeadline = new AtomicInteger();
      E2AutoSync.transferBatch(preferences, candidates, warmedUp, () -> false, () -> false, ENOUGH_SPACE, candidate -> {
        check(candidate.equals(second), "The expired batch must still resume at the next candidate");
        admittedAfterDeadline.incrementAndGet();
        return new E2AutoSync.Transfer(true, null);
      });
      check(admittedAfterDeadline.get() == 1 && warmedUp.ready == 2 && warmedUp.shouldContinue(total, 1),
          "An expired startup budget must still admit one candidate");
      E2AutoSync.transferBatch(preferences, candidates, warmedUp, () -> true, () -> false, ENOUGH_SPACE, candidate -> {
        throw new AssertionError("Cancellation must prevent the first admission");
      });
      check(!warmedUp.shouldContinue(total, 2), "No progress must not trigger a retry");

      SharedPreferences.Editor cleared = preferences.edit();
      E2AutoSync.clearBatch(cleared);
      check(cleared.commit() && new E2AutoSync.Batch(preferences, identity, total).processed() == 0,
          "Reset must discard the old cursor");
      fallback(preferences);
      outOfOrder(preferences);
      lowSpace(preferences);
      return "PASS e2-batches: drain, resume, metadata/content drift, provider failure, warm-up, "
          + "zero progress, reset, slot refill, parallel ordering and interruption, low-space fallback";
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
    E2AutoSync.transferBatch(preferences, photos, prior, () -> false, () -> false, ENOUGH_SPACE, candidate ->
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
    E2AutoSync.Batch denied = new E2AutoSync.Batch(preferences, identity, photos.size());
    denied.validateFallback(preferences, photos, candidate -> {
      throw new SecurityException("Synthetic denied original");
    }, () -> false);
    check(denied.processed() == 0, "A provider access error must invalidate an acknowledged fallback");

    AtomicBoolean stopped = new AtomicBoolean();
    try {
      new E2AutoSync.Batch(preferences, identity, photos.size()).validateFallback(preferences, photos, candidate -> {
        stopped.set(true);
        throw new SecurityException("Synthetic stop while opening original");
      }, stopped::get);
      throw new AssertionError("A stop during provider failure must halt resume validation");
    } catch (IOException expected) {
      check(stopped.get(), "Resume validation should stop when requested");
    }

    E2AutoSync.transferBatch(preferences, photos, unavailable, () -> false, () -> false, ENOUGH_SPACE, candidate ->
        new E2AutoSync.Transfer(true, null));
    check(unavailable.ready == 0 && unavailable.failed == 1,
        "Missing fallback hash cannot produce a ready result");
    SharedPreferences.Editor failed = preferences.edit();
    unavailable.save(failed);
    check(failed.commit(), "Could not persist the unavailable-original marker");
    E2AutoSync.Batch resumed = new E2AutoSync.Batch(preferences, identity, photos.size());
    resumed.validateFallback(preferences, photos, candidate -> {
      throw new UnsupportedOperationException("Synthetic provider cannot open original");
    }, () -> false);
    E2AutoSync.transferBatch(preferences, photos, resumed, () -> false, () -> false, ENOUGH_SPACE, candidate -> {
      check(candidate.equals(photos.get(1)), "The transfer should continue beyond the known failure");
      return new E2AutoSync.Transfer(true, null);
    });
    check(resumed.ready == 1 && resumed.failed == 1, "Keep the failed result while later rows progress");
  }

  private static void outOfOrder(SharedPreferences preferences) throws Exception {
    E2AutoSync.Candidate first = candidate(1, 0);
    E2AutoSync.Candidate second = candidate(2, 0);
    E2AutoSync.Candidate third = candidate(3, 0);
    List<E2AutoSync.Candidate> photos = List.of(first, second, third, candidate(4, 11));
    String identity = E2AutoSync.batchIdentity("parallel", photos);
    byte[] firstBytes = new byte[(int) first.photo().size];
    byte[] secondBytes = new byte[(int) second.photo().size];
    byte[] thirdBytes = new byte[(int) third.photo().size];
    firstBytes[0] = 1;
    secondBytes[0] = 2;
    thirdBytes[0] = 3;
    String firstHash = E2Archive.readHash(new ByteArrayInputStream(firstBytes), null, firstBytes.length);
    String secondHash = E2Archive.readHash(new ByteArrayInputStream(secondBytes), null, secondBytes.length);
    String thirdHash = E2Archive.readHash(new ByteArrayInputStream(thirdBytes), null, thirdBytes.length);
    CountDownLatch thirdStartedBeforeStop = new CountDownLatch(1);
    E2AutoSync.Batch interrupted = new E2AutoSync.Batch(preferences, identity, photos.size());
    AtomicBoolean stopped = new AtomicBoolean();
    AtomicInteger permits = new AtomicInteger(2);
    E2AutoSync.transferBatch(preferences, photos, interrupted, stopped::get,
        () -> permits.getAndDecrement() > 0, ENOUGH_SPACE, candidate -> {
          if (candidate.equals(first)) {
            await(thirdStartedBeforeStop);
            stopped.set(true);
            return new E2AutoSync.Transfer(true, firstHash);
          }
          if (candidate.equals(second)) return new E2AutoSync.Transfer(true, secondHash);
          check(candidate.equals(third), "The deadline must leave the fourth candidate for another batch");
          thirdStartedBeforeStop.countDown();
          return new E2AutoSync.Transfer(true, thirdHash);
        });
    check(stopped.get() && interrupted.processed() == 0,
        "Interrupting after a refill must not checkpoint past the pending first candidate");
    check(interrupted.reportedReady == 1 && interrupted.reportedFailed == 0,
        "The completed second transfer must still be reportable at interruption");
    SharedPreferences.Editor interruptedCheckpoint = preferences.edit();
    interrupted.save(interruptedCheckpoint);
    check(interruptedCheckpoint.commit(), "Could not persist the interrupted checkpoint");

    E2AutoSync.Batch resumed = new E2AutoSync.Batch(preferences, identity, photos.size());
    check(resumed.processed() == 0, "Resuming must retry the entire interrupted prefix");
    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch thirdStarted = new CountDownLatch(1);
    AtomicInteger active = new AtomicInteger();
    AtomicInteger peak = new AtomicInteger();
    permits.set(2);
    E2AutoSync.transferBatch(preferences, photos, resumed, () -> false,
        () -> permits.getAndDecrement() > 0, ENOUGH_SPACE, candidate -> {
          peak.accumulateAndGet(active.incrementAndGet(), Math::max);
          try {
            if (candidate.equals(first)) {
              firstStarted.countDown();
              await(thirdStarted);
              return new E2AutoSync.Transfer(false, firstHash);
            }
            if (candidate.equals(second)) {
              await(firstStarted);
              return new E2AutoSync.Transfer(true, secondHash);
            }
            check(candidate.equals(third), "The deadline must leave the fourth candidate for another batch");
            check(preferences.getInt("ready", 0) == 1 && resumed.processed() == 0,
                "Refill must follow reported completion while the checkpoint still waits for the first candidate");
            thirdStarted.countDown();
            return new E2AutoSync.Transfer(true, thirdHash);
          } finally {
            active.decrementAndGet();
          }
        });
    check(peak.get() == 2 && active.get() == 0, "Transfers must fill at most two slots and drain them");
    check(resumed.ready == 2 && resumed.failed == 1, "Each of the three outcomes must be counted exactly once");
    SharedPreferences.Editor editor = preferences.edit();
    resumed.save(editor);
    check(editor.commit(), "Parallel checkpoint must persist");
    E2AutoSync.Batch restored = new E2AutoSync.Batch(preferences, identity, photos.size());
    restored.validateFallback(preferences, photos, candidate -> {
      if (candidate.equals(first)) return new ByteArrayInputStream(firstBytes);
      if (candidate.equals(second)) return new ByteArrayInputStream(secondBytes);
      check(candidate.equals(third), "Validate only the saved fallback prefix");
      return new ByteArrayInputStream(thirdBytes);
    }, () -> false);
    check(restored.ready == 2 && restored.failed == 1,
        "Reverse completion must preserve fallback hashes in candidate order");
  }

  private static void lowSpace(SharedPreferences preferences) throws Exception {
    E2AutoSync.Candidate first = candidate(1, 11);
    E2AutoSync.Candidate second = candidate(2, 12);
    List<E2AutoSync.Candidate> photos = List.of(first, second);
    String identity = E2AutoSync.batchIdentity("low-space", photos);
    E2AutoSync.Batch batch = new E2AutoSync.Batch(preferences, identity, photos.size());
    check(preferences.edit().putInt("ready", 0).commit(), "Reset previous test progress");
    E2AutoSync.transferBatch(preferences, photos, batch, () -> false, () -> true,
        () -> E2Archive.STORAGE_RESERVE + 350, candidate -> {
          if (candidate.equals(second))
            check(preferences.getInt("ready", 0) == 1, "Wait until the first stage has been freed");
          return new E2AutoSync.Transfer(true, null);
        });
    check(batch.ready == 2, "Both originals must still fit one at a time");
  }

  private static void await(CountDownLatch latch) {
    try {
      check(latch.await(5, TimeUnit.SECONDS), "Both transfers must be admitted concurrently");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
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
