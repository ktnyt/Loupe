package jp.nano.rawgallery.camera;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import jp.nano.rawgallery.camera.SdUsbReprobe.Reader;
import jp.nano.rawgallery.camera.SdUsbReprobe.Snapshot;
import jp.nano.rawgallery.camera.SdUsbReprobe.State;
import jp.nano.rawgallery.camera.SdUsbReprobe.Volume;

public final class SdUsbReprobeTest {
  private static final String TARGET = "3066-3064";
  private static final String SOURCE = "1234-5678";
  private static final Reader READER = new Reader("usb-test", 14, 8644, 45152, 0);
  private static final Snapshot MOUNTED = snapshot("mounted", "mounted");
  private static final Snapshot EJECTED = snapshot("unmounted", "unmounted");
  private static final Snapshot TARGET_ONLY =
      new Snapshot(READER, TARGET, List.of(new Volume(TARGET, "mounted")), true);

  public static void main(String[] args) throws Exception {
    admission();
    claimAuthorization();
    runPaths();
    mountCycleAttribution();
    topology();
    System.out.println("SdUsbReprobeTest passed");
  }

  private static Snapshot snapshot(String target, String source) {
    return new Snapshot(
        READER, TARGET, List.of(new Volume(TARGET, target), new Volume(SOURCE, source)), true);
  }

  private static void admission() {
    require(MOUNTED.admission() == State.REDETECTING, "Manual refresh admits mounted cards");
    require(
        snapshot("mounted", "mounted_ro").admission() == State.REDETECTING,
        "A registered source can be read-only");
    require(
        new Snapshot(READER, TARGET, MOUNTED.volumes(), false).admission() == State.PERMISSION,
        "Permission is requested for the same admitted inventory");
    require(
        new Snapshot(READER, TARGET, List.of(new Volume(TARGET, "mounted")), true).admission()
            == State.REDETECTING,
        "The source may be absent during a stale card swap");
    for (String state :
        List.of("checking", "ejecting", "removed", "unmounted", "bad_removal", "unmountable")) {
      require(
          snapshot("mounted", state).admission() == State.UNSUPPORTED
              && snapshot(state, "mounted").admission() == State.UNSUPPORTED,
          "Transitional or ambiguous volume state is unsupported: " + state);
    }
    for (List<Volume> volumes :
        List.of(
            List.of(new Volume(SOURCE, "mounted")),
            List.of(new Volume(TARGET, "mounted"), new Volume(TARGET, "mounted")),
            List.of(new Volume(TARGET, "mounted"), new Volume(null, "mounted")),
            List.of(
                new Volume(TARGET, "mounted"),
                new Volume(SOURCE, "mounted"),
                new Volume("2345-abcd", "mounted")))) {
      require(
          new Snapshot(READER, TARGET, volumes, true).admission() == State.UNSUPPORTED,
          "Reject missing, duplicate or extra volumes");
    }
    require(
        new Snapshot(null, TARGET, MOUNTED.volumes(), true).admission() == State.UNSUPPORTED,
        "Invalid or extra USB topology is rejected");
  }

  private static void claimAuthorization() {
    var backgrounded = new SdUsbReprobe.ClaimAuthorization();
    boolean claim =
        backgrounded.tryStart(
            () -> {
              // Activity pauses after the worker's last check but before it can start claiming.
              backgrounded.cancel();
              return true;
            });
    require(!claim && backgrounded.cancelled(), "Pause wins before claim starts");

    var started = new SdUsbReprobe.ClaimAuthorization();
    require(started.tryStart(() -> true), "An active authorization starts once");
    require(!started.tryStart(() -> true), "A request cannot claim twice");
    started.invalidateBeforeClaim();
    require(!started.cancelled(), "Own remount notifications do not cancel a started claim");
    started.cancel();
    require(started.cancelled(), "Pause still stops remount polling after claim starts");
  }

  private static void runPaths() throws Exception {
    Fake fake = new Fake();
    require(
        run(fake, new ReentrantLock(), () -> false, 0) == State.READY
            && fake.calls.equals(
                List.of("open", "claim", "release", "close", "waiting", "source", "restored")),
        "Mounted manual admission still validates a remount and both volumes after cleanup");

    fake = new Fake();
    fake.beforeClaim = EJECTED;
    require(
        run(fake, new ReentrantLock(), () -> false, 0) == State.CHANGED
            && fake.calls.equals(List.of("open", "close")),
        "A remount before claim closes the handle without claiming");

    fake = new Fake();
    fake.beforeClaim = new Snapshot(READER, TARGET, MOUNTED.volumes(), false);
    require(
        run(fake, new ReentrantLock(), () -> false, 0) == State.DENIED
            && fake.calls.equals(List.of("open", "close")),
        "Permission is checked again immediately before claim");

    fake = new Fake();
    fake.beforeClaim = new Snapshot(READER, SOURCE, MOUNTED.volumes(), true);
    require(
        run(fake, new ReentrantLock(), () -> false, 0) == State.CHANGED
            && fake.calls.equals(List.of("open", "close")),
        "Selected destination cannot change");

    fake = new Fake();
    fake.beforeClaim =
        new Snapshot(
            new Reader(READER.name(), 15, 8644, 45152, 0), TARGET, MOUNTED.volumes(), true);
    require(
        run(fake, new ReentrantLock(), () -> false, 0) == State.CHANGED
            && fake.calls.equals(List.of("open", "close")),
        "A replacement reader with an identical model cannot be claimed");

    fake = new Fake();
    fake.shouldClaim = false;
    require(
        run(fake, new ReentrantLock(), () -> false, 0) == State.CANCELLED
            && fake.calls.equals(List.of("open", "close")),
        "An invalidated foreground request cannot claim an already opened connection");

    for (String failure : List.of("claim", "claim_false", "release", "release_false", "close")) {
      fake = new Fake();
      fake.failure = failure;
      require(
          run(fake, new ReentrantLock(), () -> false, 0) == State.FAILED
              && fake.calls.equals(List.of("open", "claim", "release", "close")),
          "Always release and close when claim was attempted: " + failure);
    }
    fake = new Fake();
    fake.failure = "open";
    require(
        run(fake, new ReentrantLock(), () -> false, 0) == State.FAILED
            && fake.calls.equals(List.of("open")),
        "Open failure leaves no USB handle");

    fake = new Fake();
    AtomicBoolean cancel = fake.cancel;
    fake.failure = "cancel_at_claim";
    require(
        run(fake, new ReentrantLock(), cancel::get, 0) == State.CANCELLED
            && fake.calls.equals(List.of("open", "claim", "release", "close", "waiting")),
        "Lifecycle cancellation during claim still releases and closes");

    fake = new Fake();
    fake.restored = EJECTED;
    require(
        run(fake, new ReentrantLock(), () -> false, 0) == State.TIMEOUT,
        "Claim success alone is never ready");

    fake = new Fake();
    fake.snapshotOnSourceDetach = null;
    require(
        run(fake, new ReentrantLock(), () -> false, 0) == State.TIMEOUT
            && !fake.calls.contains("source"),
        "An unchanged mounted snapshot without a new cycle is not recovery");

    fake = new Fake();
    fake.snapshotOnSourceDetach = MOUNTED;
    require(
        run(fake, new ReentrantLock(), () -> false, 2) == State.TIMEOUT
            && !fake.calls.contains("source"),
        "A late named detach with the source continuously mounted is not remount evidence");

    fake = new Fake();
    fake.snapshotOnSourceDetach = null;
    fake.postRelease.add(TARGET_ONLY);
    require(
        run(fake, new ReentrantLock(), () -> false, 2) == State.READY
            && fake.calls.contains("source"),
        "A trusted target-only snapshot then same source return proves disappearance");

    fake = new Fake();
    fake.snapshotOnSourceDetach = null;
    fake.postRelease.add(new Snapshot(READER, TARGET, List.of(), true));
    require(
        run(fake, new ReentrantLock(), () -> false, 2) == State.TIMEOUT,
        "A failed or empty inventory is not a trustworthy source disappearance");

    fake = new Fake();
    fake.grant = false;
    require(
        run(fake, new ReentrantLock(), () -> false, 0) == State.SOURCE_REQUIRED,
        "Unreachable or ungranted source requests selection");
    fake = new Fake();
    fake.snapshotOnSourceDetach = null;
    fake.expectedSource = "2345-abcd";
    fake.restored =
        new Snapshot(
            READER,
            TARGET,
            List.of(new Volume(TARGET, "mounted"), new Volume(fake.expectedSource, "mounted")),
            true);
    require(
        run(fake, new ReentrantLock(), () -> false, 0) == State.READY
            && fake.calls.contains("source"),
        "A changed source can be ready only after checking the current registration");

    fake = new Fake();
    Snapshot pending = new Snapshot(READER, TARGET, List.of(), true);
    fake.postRelease.add(pending);
    fake.postRelease.add(
        new Snapshot(
            READER,
            TARGET,
            List.of(new Volume(TARGET, "mounted"), new Volume(null, "unmounted")),
            true));
    require(
        run(fake, new ReentrantLock(), () -> false, 5) == State.READY,
        "Missing and partial inventories after release are retried while the known connection remains");
    fake = new Fake();
    fake.restored = pending;
    require(
        run(fake, new ReentrantLock(), () -> false, 0) == State.TIMEOUT,
        "A persistently unavailable inventory cannot become ready before timeout");
    fake = new Fake();
    fake.restored = new Snapshot(READER, SOURCE, List.of(), true);
    require(
        run(fake, new ReentrantLock(), () -> false, 0) == State.CHANGED,
        "A changed selected destination fails even while inventory is pending");

    var busy = new ReentrantLock();
    var acquired = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    Thread other =
        new Thread(
            () -> {
              busy.lock();
              try {
                acquired.countDown();
                release.await();
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              } finally {
                busy.unlock();
              }
            });
    other.start();
    acquired.await();
    try {
      fake = new Fake();
      require(
          run(fake, busy, () -> false, 0) == State.BUSY && fake.calls.isEmpty(),
          "No USB or MediaStore work while an import or move owns the gate");
    } finally {
      release.countDown();
      other.join();
    }
  }

  private static void mountCycleAttribution() {
    var cycle = new SdUsbReprobe.MountCycle(MOUNTED);
    String different = "ABCD-1234";
    Snapshot newSource =
        new Snapshot(
            READER,
            TARGET,
            List.of(new Volume(TARGET, "mounted"), new Volume(different, "mounted")),
            true);
    require(cycle.suppresses(different, newSource), "Own mount during the handoff is suppressed");
    cycle.finish(newSource);
    require(
        cycle.suppresses(different, newSource) && cycle.suppresses(different, newSource),
        "Even a late or repeated broadcast for the restored cycle cannot start auto-import");
    require(
        !cycle.expired(newSource, different),
        "A queued detach broadcast cannot clear a source Android currently reports mounted");
    require(
        !cycle.suppresses(SOURCE, MOUNTED),
        "A genuinely different currently mounted source preserves automatic import");
    require(
        cycle.expired(
            new Snapshot(READER, TARGET, List.of(new Volume(TARGET, "mounted")), true), different),
        "Recognized removal of the restored source ends attribution");
    require(
        cycle.expired(new Snapshot(null, null, List.of(), false), null),
        "A different connection ends attribution");

    cycle = new SdUsbReprobe.MountCycle(MOUNTED);
    cycle.finish(null);
    require(
        !cycle.expired(MOUNTED, SOURCE) && cycle.suppresses(SOURCE, MOUNTED),
        "After failure a delayed notice cannot expire attribution for the still-mounted source");
    require(
        cycle.expired(TARGET_ONLY, SOURCE),
        "After failure an observed actual removal clears attribution before an independent mount");
    require(
        cycle.suppresses(different, newSource),
        "Without an observed removal, timeout cannot replay the first late mount as a new automatic import");
    require(
        !cycle.suppresses(SOURCE, MOUNTED),
        "Once current, late-source attribution stays scoped to that source");
  }

  private static void topology() {
    String tested =
        "Xiaomi/dada_global/dada:16/BP2A.250605.031.A3/OS3.0.304.0.WOCMIXM:user/release-keys";
    require(
        SdUsbReprobe.supportedPhone("dada", 36, tested),
        "Only the tested stock configuration is allowed");
    require(
        !SdUsbReprobe.supportedPhone("other", 36, tested)
            && !SdUsbReprobe.supportedPhone("dada", 35, tested)
            && !SdUsbReprobe.supportedPhone("dada", 36, tested.replace("304", "305")),
        "A different phone, Android version or release is rejected");
    var primary = new SdUsbReprobe.VisibleVolume(null, "mounted", true, true, false);
    var target = new SdUsbReprobe.VisibleVolume(TARGET, "mounted", false, false, true);
    var source = new SdUsbReprobe.VisibleVolume(SOURCE, "mounted", false, false, true);
    require(
        SdUsbReprobe.portableLayout(List.of(primary, target, source)).size() == 2,
        "One internal primary and two identifiable portable volumes");
    require(
        SdUsbReprobe.portableLayout(List.of(primary, target)).size() == 1,
        "The source may not yet be visible on the tested hardware");
    for (var refused :
        List.of(
            new SdUsbReprobe.VisibleVolume("2233-4455", "mounted", false, true, false),
            new SdUsbReprobe.VisibleVolume(null, "mounted", false, false, true),
            new SdUsbReprobe.VisibleVolume("2233-4455", "mounted", false, false, false),
            primary,
            target)) {
      require(
          SdUsbReprobe.portableLayout(List.of(primary, target, refused)) == null,
          "Unexpected layout cannot authorize a handoff");
    }
    require(
        SdUsbReprobe.portableLayout(List.of(target, source)) == null,
        "Missing internal primary is uncertain");
    var extra = new SdUsbReprobe.VisibleVolume("2233-4455", "mounted", false, false, true);
    require(
        SdUsbReprobe.portableLayout(List.of(primary, target, source, extra)) == null,
        "An extra portable volume is not an allowed topology");
  }

  private static State run(Fake fake, ReentrantLock gate, BooleanSupplier cancelled, long timeout)
      throws InterruptedException {
    return SdUsbReprobe.run(
        MOUNTED,
        fake,
        gate,
        cancelled,
        new AtomicLong()::getAndIncrement,
        timeout,
        new SdUsbReprobe.Observer() {
          @Override
          public boolean startingClaim() {
            return fake.shouldClaim;
          }

          @Override
          public void waiting() {
            fake.calls.add("waiting");
          }

          @Override
          public Snapshot snapshotOnSourceDetach() {
            return fake.snapshotOnSourceDetach;
          }

          @Override
          public long detachGeneration() {
            return 0;
          }

          @Override
          public void restored(Snapshot current, long generation) {
            fake.calls.add("restored");
          }
        });
  }

  private static final class Fake implements SdUsbReprobe.Platform, SdUsbReprobe.Session {
    final List<String> calls = new ArrayList<>();
    final ArrayDeque<Snapshot> postRelease = new ArrayDeque<>();
    final AtomicBoolean cancel = new AtomicBoolean();
    Snapshot beforeClaim = MOUNTED;
    Snapshot restored = MOUNTED;
    boolean grant = true;
    boolean shouldClaim = true;
    Snapshot snapshotOnSourceDetach = TARGET_ONLY;
    String expectedSource = SOURCE;
    String failure = "";

    @Override
    public Snapshot snapshot() {
      if (calls.contains("close")) return postRelease.isEmpty() ? restored : postRelease.remove();
      return calls.contains("open") ? beforeClaim : MOUNTED;
    }

    @Override
    public SdUsbReprobe.Session open(Reader reader) {
      calls.add("open");
      return failure.equals("open") ? null : this;
    }

    @Override
    public boolean destinationWritable(String destination) {
      return restored.volumes().stream()
          .anyMatch(volume -> TARGET.equals(volume.uuid()) && "mounted".equals(volume.state()));
    }

    @Override
    public boolean sourceReachable(String source, String destination, BooleanSupplier stopped) {
      require(
          expectedSource.equals(source) && TARGET.equals(destination),
          "Check the current distinct source UUID");
      calls.add("source");
      return grant;
    }

    @Override
    public boolean claim() {
      calls.add("claim");
      if (failure.equals("claim")) throw new IllegalStateException();
      if (failure.equals("cancel_at_claim")) cancel.set(true);
      return !failure.equals("claim_false");
    }

    @Override
    public boolean release() {
      calls.add("release");
      if (failure.equals("release")) throw new IllegalStateException();
      return !failure.equals("release_false");
    }

    @Override
    public void close() {
      calls.add("close");
      if (failure.equals("close")) throw new IllegalStateException();
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
