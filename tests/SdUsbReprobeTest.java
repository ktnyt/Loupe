package jp.nano.rawgallery.camera;

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
    runPaths();
    ownMounts();
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

  private static void runPaths() throws Exception {
    Fake fake = new Fake();
    require(
        run(null, fake, new ReentrantLock(), () -> false, 0) == State.EJECT_REQUIRED
            && fake.calls.isEmpty(),
        "A cold ejected snapshot cannot claim");
    require(
        run(EJECTION, fake, new ReentrantLock(), () -> false, 0) == State.READY
            && fake.calls.equals(
                List.of("open", "suppress", "claim", "release", "close", "waiting", "source")),
        "Recheck, suppress mount events before the USB step, release, close, then verify both cards");

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
              && fake.calls.equals(List.of("open", "suppress", "claim", "release", "close")),
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
            && fake.calls.equals(
                List.of("open", "suppress", "claim", "release", "close", "waiting")),
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
        run(EJECTION, fake, new ReentrantLock(), () -> false, 0) == State.SOURCE_REQUIRED
            && !fake.calls.contains("source"),
        "A new source is not reused automatically");

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

  private static void ownMounts() {
    var suppress = new SdUsbReprobe.MountSuppression();
    Object first = new Object();
    Object second = new Object();
    suppress.begin(first, EJECTED);
    require(suppress.suppresses(reader -> false), "In-flight operation suppresses any mount");
    suppress.begin(second, EJECTED);
    suppress.finish(first, MOUNTED);
    require(
        suppress.suppresses(reader -> false), "Old completion cannot release current ownership");
    suppress.finish(second, MOUNTED);
    require(suppress.suppresses(READER::equals), "Own delayed mount is ignored after close");
    suppress.sourceUnmounted(SOURCE, () -> MOUNTED, target -> true);
    require(
        suppress.suppresses(READER::equals),
        "Delayed unmount cannot clear a currently mounted source");
    suppress.sourceUnmounted(SOURCE, () -> snapshot("mounted", "unmounted"), target -> false);
    require(
        suppress.suppresses(READER::equals),
        "Source unmount must leave the same destination usable");

    for (String removed : List.of("unmounted", "removed", "bad_removal")) {
      suppress.begin(second, EJECTED);
      suppress.finish(second, MOUNTED);
      suppress.sourceUnmounted(SOURCE, () -> snapshot("mounted", removed), target -> true);
      require(
          !suppress.suppresses(READER::equals),
          "Later real source removal re-enables automatic import");
    }
    suppress.begin(second, EJECTED);
    suppress.finish(second, MOUNTED);
    suppress.sourceUnmounted(
        SOURCE,
        () -> new Snapshot(READER, TARGET, List.of(new Volume(TARGET, "mounted")), true),
        target -> true);
    require(
        !suppress.suppresses(READER::equals),
        "An observed eject can remove the old UUID from the inventory");

    suppress.begin(second, EJECTED);
    suppress.finish(second, null);
    suppress.sourceUnmounted(SOURCE, () -> snapshot("mounted", "unmounted"), target -> true);
    require(
        suppress.suppresses(READER::equals),
        "A failed restoration never treats late events as a new copy");
    require(
        !suppress.suppresses(reader -> false), "A proven physical USB detach ends old suppression");
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
          public void beforeClaim() {
            fake.calls.add("suppress");
          }

          @Override
          public void waiting() {
            fake.calls.add("waiting");
          }
        });
  }

  private static final class Fake implements SdUsbReprobe.Platform, SdUsbReprobe.Session {
    final List<String> calls = new ArrayList<>();
    final AtomicBoolean cancel = new AtomicBoolean();
    Snapshot beforeClaim = EJECTED;
    Snapshot restored = MOUNTED;
    boolean grant = true;
    String failure = "";

    @Override
    public Snapshot snapshot() {
      if (calls.contains("close")) return restored;
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
