package jp.nano.rawgallery.camera;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    EJECT_REQUIRED,
    CONFIRM_EJECTION,
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

  record Reader(String name, int deviceId, int vendor, int product, int interfaceId) {
    boolean supported() {
      return name != null && vendor == VENDOR && product == PRODUCT && interfaceId >= 0;
    }
  }

  record Volume(String uuid, String state) {}

  record Snapshot(Reader reader, String destination, List<Volume> volumes, boolean permitted) {
    Snapshot {
      volumes = List.copyOf(volumes);
    }

    State admission() {
      if (reader == null || !reader.supported() || destination == null) return State.UNSUPPORTED;
      Set<String> seen = new HashSet<>();
      for (Volume volume : volumes) {
        if (volume.uuid == null || !seen.add(volume.uuid) || !"unmounted".equals(volume.state)) {
          return State.EJECT_REQUIRED;
        }
      }
      if (seen.size() > 2 || !seen.contains(destination)) return State.EJECT_REQUIRED;
      return permitted ? State.REDETECTING : State.PERMISSION;
    }

    boolean canPrepareEjection() {
      if (reader == null || !reader.supported() || destination == null) return false;
      Set<String> seen = new HashSet<>();
      for (Volume volume : volumes) {
        if (volume.uuid == null
            || !seen.add(volume.uuid)
            || !("mounted".equals(volume.state)
                || (!destination.equals(volume.uuid) && "mounted_ro".equals(volume.state))))
          return false;
      }
      return seen.size() <= 2 && seen.contains(destination);
    }

    boolean sameInventory(Snapshot current) {
      if (!sameConnection(current) || volumes.size() != current.volumes.size()) return false;
      Set<String> expected = new HashSet<>();
      for (Volume volume : volumes) expected.add(volume.uuid);
      Set<String> actual = new HashSet<>();
      for (Volume volume : current.volumes) actual.add(volume.uuid);
      return expected.equals(actual);
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
  }

  record Ejection(Snapshot beforeSettings, long expiresAt) {
    Ejection {
      if (!beforeSettings.canPrepareEjection())
        throw new IllegalArgumentException("No mounted inventory");
    }

    boolean matches(Snapshot current, long now) {
      return now < expiresAt && beforeSettings.sameInventory(current);
    }

    boolean confirmed(Snapshot current, long now) {
      State state = current.admission();
      return matches(current, now) && (state == State.REDETECTING || state == State.PERMISSION);
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

    void restored(Snapshot current);
  }

  static State run(
      Ejection ejection,
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
      if (ejection == null || !ejection.confirmed(initial, now.getAsLong()))
        return State.EJECT_REQUIRED;
      if (!initial.permitted()) return State.DENIED;
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
        if (!ejection.confirmed(current, now.getAsLong())) return State.EJECT_REQUIRED;
        if (!observer.startingClaim()) return State.CANCELLED;
        if (!claimAndRelease(usb)) return State.FAILED;
      }
      observer.waiting();
      long deadline = now.getAsLong() + waitMillis;
      while (!cancelled.getAsBoolean()) {
        current = platform.snapshot();
        if (!initial.sameConnection(current)) return State.CHANGED;
        if (!current.permitted()) return State.DENIED;
        if (platform.destinationWritable(current.destination)) {
          String source = readableSource(current);
          if (source != null) {
            boolean reachable = platform.sourceReachable(source, current.destination, cancelled);
            if (cancelled.getAsBoolean()) return State.CANCELLED;
            Snapshot verified = platform.snapshot();
            if (!initial.sameConnection(verified)) return State.CHANGED;
            if (!verified.permitted()) return State.DENIED;
            if (current.sameAdmission(verified)) {
              observer.restored(verified);
              return reachable ? State.READY : State.SOURCE_REQUIRED;
            }
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
      } else if ("mounted".equals(volume.state) || "mounted_ro".equals(volume.state)) {
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
