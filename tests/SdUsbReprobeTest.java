package jp.nano.rawgallery.camera;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import jp.nano.rawgallery.camera.SdUsbReprobe.Ejection;
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
  private static final Ejection EJECTION = new Ejection(MOUNTED, 100);

  public static void main(String[] args) throws Exception {
    admission();
    claimAuthorization();
    runPaths();
    topology();
    System.out.println("SdUsbReprobeTest passed");
  }

  private static Snapshot snapshot(String target, String source) {
    return new Snapshot(
        READER, TARGET, List.of(new Volume(TARGET, target), new Volume(SOURCE, source)), true);
  }

  private static void admission() {
    require(
        MOUNTED.canPrepareEjection(), "A mounted, positively identified inventory can be armed");
    require(
        !snapshot("mounted_ro", "mounted").canPrepareEjection(),
        "Target must be writable before ejection");
    require(!EJECTED.canPrepareEjection(), "An unmounted entry alone cannot arm ejection");
    require(
        EJECTION.confirmed(EJECTED, 99),
        "Same, fully ejected inventory can reach foreground confirmation");
    require(
        !EJECTION.confirmed(EJECTED, 100),
        "An expired confirmation does not authorize the USB operation");
    for (String state :
        List.of(
            "mounted",
            "mounted_ro",
            "checking",
            "ejecting",
            "removed",
            "bad_removal",
            "unknown",
            "unmountable")) {
      require(
          !EJECTION.confirmed(snapshot("unmounted", state), 10),
          "Reject unsafe source state " + state);
      require(
          !EJECTION.confirmed(snapshot(state, "unmounted"), 10),
          "Reject unsafe destination state " + state);
    }
    for (List<Volume> volumes :
        List.of(
            List.of(new Volume(SOURCE, "unmounted")),
            List.of(new Volume(TARGET, "unmounted"), new Volume(TARGET, "unmounted")),
            List.of(new Volume(TARGET, "unmounted"), new Volume("2345-abcd", "unmounted")),
            List.of(new Volume(TARGET, "unmounted"), new Volume(null, "unmounted")),
            List.of(
                new Volume(TARGET, "unmounted"),
                new Volume(SOURCE, "unmounted"),
                new Volume("2345-abcd", "unmounted")))) {
      require(
          !EJECTION.confirmed(new Snapshot(READER, TARGET, volumes, true), 10),
          "Reject missing, changed, duplicate or extra volumes");
    }
    require(
        !EJECTION.confirmed(new Snapshot(null, TARGET, EJECTED.volumes(), true), 10),
        "Invalid or extra USB topology is rejected");
    require(
        !EJECTION.confirmed(
            new Snapshot(
                new Reader(READER.name(), 15, 8644, 45152, 0), TARGET, EJECTED.volumes(), true),
            10),
        "New USB connection with the same model is rejected");
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
    started.invalidateBeforeClaim();
    require(!started.cancelled(), "Own remount notifications do not cancel a started claim");
    started.cancel();
    require(started.cancelled(), "Pause still stops remount polling after claim starts");
  }

  private static void runPaths() throws Exception {
    Fake fake = new Fake();
    require(
        run(null, fake, new ReentrantLock(), () -> false, 0) == State.EJECT_REQUIRED
            && fake.calls.isEmpty(),
        "A cold ejected snapshot cannot claim");
    require(
        run(EJECTION, fake, new ReentrantLock(), () -> false, 0) == State.READY
            && fake.calls.equals(
                List.of("open", "claim", "release", "close", "waiting", "source", "restored")),
        "Recheck the admitted volumes, release and close, then verify both cards");

    fake = new Fake();
    fake.beforeClaim = MOUNTED;
    require(
        run(EJECTION, fake, new ReentrantLock(), () -> false, 0) == State.CHANGED
            && fake.calls.equals(List.of("open", "close")),
        "A remount before claim closes the handle without claiming");

    fake = new Fake();
    fake.beforeClaim = new Snapshot(READER, TARGET, EJECTED.volumes(), false);
    require(
        run(EJECTION, fake, new ReentrantLock(), () -> false, 0) == State.DENIED
            && fake.calls.equals(List.of("open", "close")),
        "Permission is checked again immediately before claim");

    fake = new Fake();
    fake.beforeClaim = new Snapshot(READER, SOURCE, EJECTED.volumes(), true);
    require(
        run(EJECTION, fake, new ReentrantLock(), () -> false, 0) == State.CHANGED
            && fake.calls.equals(List.of("open", "close")),
        "Selected destination cannot change");

    for (String failure : List.of("claim", "claim_false", "release", "release_false", "close")) {
      fake = new Fake();
      fake.failure = failure;
      require(
          run(EJECTION, fake, new ReentrantLock(), () -> false, 0) == State.FAILED
              && fake.calls.equals(List.of("open", "claim", "release", "close")),
          "Always release and close when claim was attempted: " + failure);
    }
    fake = new Fake();
    fake.failure = "open";
    require(
        run(EJECTION, fake, new ReentrantLock(), () -> false, 0) == State.FAILED
            && fake.calls.equals(List.of("open")),
        "Open failure leaves no USB handle");

    fake = new Fake();
    AtomicBoolean cancel = fake.cancel;
    fake.failure = "cancel_at_claim";
    require(
        run(EJECTION, fake, new ReentrantLock(), cancel::get, 0) == State.CANCELLED
            && fake.calls.equals(List.of("open", "claim", "release", "close", "waiting")),
        "Lifecycle cancellation during claim still releases and closes");

    fake = new Fake();
    fake.restored = EJECTED;
    require(
        run(EJECTION, fake, new ReentrantLock(), () -> false, 0) == State.TIMEOUT,
        "Claim success alone is never ready");

    fake = new Fake();
    fake.grant = false;
    require(
        run(EJECTION, fake, new ReentrantLock(), () -> false, 0) == State.SOURCE_REQUIRED,
        "Unreachable or ungranted source requests selection");
    fake = new Fake();
    fake.restored =
        new Snapshot(
            READER,
            TARGET,
            List.of(new Volume(TARGET, "mounted"), new Volume("2345-abcd", "mounted")),
            true);
    require(
        run(EJECTION, fake, new ReentrantLock(), () -> false, 0) == State.READY
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
        run(EJECTION, fake, new ReentrantLock(), () -> false, 5) == State.READY,
        "Missing and partial inventories after release are retried while the known connection remains");
    fake = new Fake();
    fake.restored = pending;
    require(
        run(EJECTION, fake, new ReentrantLock(), () -> false, 0) == State.TIMEOUT,
        "A persistently unavailable inventory cannot become ready before timeout");
    fake = new Fake();
    fake.restored = new Snapshot(READER, SOURCE, List.of(), true);
    require(
        run(EJECTION, fake, new ReentrantLock(), () -> false, 0) == State.CHANGED,
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
          run(EJECTION, fake, busy, () -> false, 0) == State.BUSY && fake.calls.isEmpty(),
          "No USB or MediaStore work while an import or move owns the gate");
    } finally {
      release.countDown();
      other.join();
    }
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

  private static State run(
      Ejection ejection, Fake fake, ReentrantLock gate, BooleanSupplier cancelled, long timeout)
      throws InterruptedException {
    return SdUsbReprobe.run(
        ejection,
        EJECTED,
        fake,
        gate,
        cancelled,
        () -> 10,
        timeout,
        new SdUsbReprobe.Observer() {
          @Override
          public boolean startingClaim() {
            return true;
          }

          @Override
          public void waiting() {
            fake.calls.add("waiting");
          }

          @Override
          public void restored(Snapshot current) {
            fake.calls.add("restored");
          }
        });
  }

  private static final class Fake implements SdUsbReprobe.Platform, SdUsbReprobe.Session {
    final List<String> calls = new ArrayList<>();
    final ArrayDeque<Snapshot> postRelease = new ArrayDeque<>();
    final AtomicBoolean cancel = new AtomicBoolean();
    Snapshot beforeClaim = EJECTED;
    Snapshot restored = MOUNTED;
    boolean grant = true;
    String failure = "";

    @Override
    public Snapshot snapshot() {
      if (calls.contains("close")) return postRelease.isEmpty() ? restored : postRelease.remove();
      return calls.contains("open") ? beforeClaim : EJECTED;
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
