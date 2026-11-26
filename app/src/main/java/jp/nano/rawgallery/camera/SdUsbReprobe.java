package jp.nano.rawgallery.camera;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Only the tested dual-slot reader is eligible for this USB interface handoff. */
final class SdUsbReprobe {
  static final int VENDOR = 8644;
  static final int PRODUCT = 45152;
  private static final String FINGERPRINT =
      "Xiaomi/dada_global/dada:16/BP2A.250605.031.A3/OS3.0.304.0.WOCMIXM:user/release-keys";

  static boolean supportedPhone(String device, int sdk, String fingerprint) {
    return "dada".equals(device) && sdk == 36 && FINGERPRINT.equals(fingerprint);
  }

  record VisibleVolume(
      String uuid, String state, boolean primary, boolean emulated, boolean removable) {}

  static List<Volume> portableLayout(List<VisibleVolume> visible) {
    List<Volume> portable = new ArrayList<>();
    Set<String> uuids = new HashSet<>();
    boolean primary = false;
    for (VisibleVolume volume : visible) {
      if (volume.primary && volume.emulated && !volume.removable && volume.uuid == null) {
        if (primary || !"mounted".equals(volume.state)) return null;
        primary = true;
      } else if (volume.primary
          || volume.emulated
          || !volume.removable
          || volume.uuid == null
          || !uuids.add(volume.uuid)
          || uuids.size() > 2) {
        return null;
      } else {
        portable.add(new Volume(volume.uuid, volume.state));
      }
    }
    return primary ? portable : null;
  }

  enum State {
    UNSUPPORTED,
    PERMISSION,
    REDETECTING,
    WAITING,
    SOURCE_REQUIRED,
    READY,
    BUSY,
    CHANGED,
    DENIED,
    TIMEOUT,
    FAILED,
    CANCELLED
  }

  static final class ClaimAuthorization {
    private enum Phase {
      PENDING,
      STARTED,
      INVALIDATED,
      CANCELLED
    }

    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.PENDING);

    boolean tryStart(BooleanSupplier stillValid) {
      return stillValid.getAsBoolean() && phase.compareAndSet(Phase.PENDING, Phase.STARTED);
    }

    void invalidateBeforeClaim() {
      phase.compareAndSet(Phase.PENDING, Phase.INVALIDATED);
    }

    void cancel() {
      phase.set(Phase.CANCELLED);
    }

    boolean cancelled() {
      Phase current = phase.get();
      return current == Phase.CANCELLED || current == Phase.INVALIDATED;
    }
  }

  record Reader(String name, int deviceId, int vendor, int product, int interfaceId) {
    boolean supported() {
      return name != null && vendor == VENDOR && product == PRODUCT && interfaceId >= 0;
    }
  }

  record Volume(String uuid, String state) {}

  private static boolean readable(String state) {
    return "mounted".equals(state) || "mounted_ro".equals(state);
  }

  record Snapshot(Reader reader, String destination, List<Volume> volumes, boolean permitted) {
    Snapshot {
      volumes = List.copyOf(volumes);
    }

    State admission() {
      if (reader == null || !reader.supported() || destination == null) return State.UNSUPPORTED;
      Set<String> seen = new HashSet<>();
      for (Volume volume : volumes) {
        if (volume.uuid == null
            || !seen.add(volume.uuid)
            || !(destination.equals(volume.uuid)
                ? "mounted".equals(volume.state)
                : readable(volume.state))) return State.UNSUPPORTED;
      }
      if (seen.size() > 2 || !seen.contains(destination)) return State.UNSUPPORTED;
      return permitted ? State.REDETECTING : State.PERMISSION;
    }

    boolean sameConnection(Snapshot current) {
      return reader != null
          && reader.equals(current.reader)
          && destination != null
          && destination.equals(current.destination);
    }

    boolean sameAdmission(Snapshot current) {
      return sameConnection(current) && Set.copyOf(volumes).equals(Set.copyOf(current.volumes));
    }

    boolean sourceUnavailable(String source) {
      if (source == null) return false;
      for (Volume volume : volumes) {
        if (source.equals(volume.uuid)) {
          return volume.state != null && !readable(volume.state);
        }
      }
      // An empty or invalid inventory can also mean Android's query failed.
      return volumes.equals(List.of(new Volume(destination, "mounted")));
    }
  }

  /** Attributes notifications on the same mounted source until Android observes another cycle. */
  static final class MountCycle {
    private final Snapshot initial;
    private boolean finished, waitingForLateMount;
    private String source;

    MountCycle(Snapshot initial) {
      this.initial = initial;
      this.source = readableSource(initial);
    }

    synchronized void finish(Snapshot restored) {
      if (restored != null) source = readableSource(restored);
      waitingForLateMount = restored == null;
      finished = true;
    }

    synchronized boolean suppresses(String notifiedSource, Snapshot current) {
      if (!initial.sameConnection(current)) return false;
      if (!finished) return true;
      String mounted = readableSource(current);
      // Without an observed removal, the next new readable source can still be the handoff's
      // delayed remount. Keep the old source as an expiry boundary until then.
      if (waitingForLateMount
          && notifiedSource != null
          && notifiedSource.equals(mounted)
          && !notifiedSource.equals(source)) {
        source = mounted;
        waitingForLateMount = false;
      }
      return source == null || source.equals(notifiedSource);
    }

    synchronized boolean expired(Snapshot current, String detached) {
      if (!initial.sameConnection(current)) return true;
      if (!finished || source == null || !source.equals(detached)) return false;
      // A queued notice cannot clear the attribution while Android still reports that source.
      return current.sourceUnavailable(source);
    }
  }

  interface Session extends AutoCloseable {
    boolean claim();

    boolean release();

    @Override
    void close();
  }

  interface Platform {
    Snapshot snapshot();

    Session open(Reader reader);

    boolean destinationWritable(String destination);

    boolean sourceReachable(String source, String destination, BooleanSupplier cancelled);
  }

  interface Observer {
    boolean startingClaim();

    void waiting();

    Snapshot snapshotOnSourceDetach();

    long detachGeneration();

    void restored(Snapshot current, long detachGeneration);
  }

  static State run(
      Snapshot initial,
      Platform platform,
      Lock lock,
      BooleanSupplier cancelled,
      LongSupplier now,
      long waitMillis,
      Observer observer)
      throws InterruptedException {
    if (!lock.tryLock()) return State.BUSY;
    try {
      if (cancelled.getAsBoolean()) return State.CANCELLED;
      State admission = initial.admission();
      if (admission == State.UNSUPPORTED) return admission;
      if (admission == State.PERMISSION) return State.DENIED;
      Snapshot current = platform.snapshot();
      if (!initial.sameAdmission(current)) return State.CHANGED;
      if (!current.permitted()) return State.DENIED;
      try (Session usb = platform.open(initial.reader)) {
        if (usb == null) return State.FAILED;
        if (cancelled.getAsBoolean()) return State.CANCELLED;
        current = platform.snapshot();
        if (!initial.sameAdmission(current)) return State.CHANGED;
        if (!current.permitted()) return State.DENIED;
        if (cancelled.getAsBoolean()) return State.CANCELLED;
        if (!observer.startingClaim()) return State.CANCELLED;
        if (!claimAndRelease(usb)) return State.FAILED;
      }
      observer.waiting();
      long deadline = now.getAsLong() + waitMillis;
      String previousSource = readableSource(initial);
      boolean sourceDisappeared = false;
      while (!cancelled.getAsBoolean()) {
        long generation = observer.detachGeneration();
        current = platform.snapshot();
        if (!initial.sameConnection(current)) return State.CHANGED;
        if (!current.permitted()) return State.DENIED;
        Snapshot observed = observer.snapshotOnSourceDetach();
        sourceDisappeared |=
            current.sourceUnavailable(previousSource)
                || (observed != null
                    && initial.sameConnection(observed)
                    && observed.sourceUnavailable(previousSource));
        String source = readableSource(current);
        // The pre-claim UUID and mounted state can both be stale after a card swap.
        if (source != null
            && (!source.equals(previousSource) || sourceDisappeared)
            && platform.destinationWritable(current.destination)) {
          boolean reachable = platform.sourceReachable(source, current.destination, cancelled);
          if (cancelled.getAsBoolean()) return State.CANCELLED;
          Snapshot verified = platform.snapshot();
          if (!initial.sameConnection(verified)) return State.CHANGED;
          if (!verified.permitted()) return State.DENIED;
          if (current.sameAdmission(verified)
              && generation == observer.detachGeneration()
              && platform.destinationWritable(verified.destination)) {
            observer.restored(verified, generation);
            return reachable ? State.READY : State.SOURCE_REQUIRED;
          }
        }
        long remaining = deadline - now.getAsLong();
        if (remaining <= 0) return State.TIMEOUT;
        Thread.sleep(Math.min(remaining, 200));
      }
      return State.CANCELLED;
    } catch (RuntimeException unavailable) {
      return cancelled.getAsBoolean() ? State.CANCELLED : State.FAILED;
    } finally {
      lock.unlock();
    }
  }

  static String readableSource(Snapshot current) {
    if (current.volumes.size() != 2) return null;
    boolean writableTarget = false;
    String source = null;
    for (Volume volume : current.volumes) {
      if (volume.uuid == null) return null;
      if (volume.uuid.equals(current.destination)) {
        writableTarget = "mounted".equals(volume.state);
      } else if (readable(volume.state)) {
        source = volume.uuid;
      }
    }
    return writableTarget ? source : null;
  }

  private static boolean claimAndRelease(Session usb) {
    boolean released;
    boolean claimed;
    try {
      claimed = usb.claim();
    } finally {
      released = usb.release();
    }
    return claimed && released;
  }

  private SdUsbReprobe() {}
}
