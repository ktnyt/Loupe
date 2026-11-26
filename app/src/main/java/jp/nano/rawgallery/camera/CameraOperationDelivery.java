package jp.nano.rawgallery.camera;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.BadParcelableException;
import android.os.Binder;
import android.os.Bundle;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** One-shot admission carried by an explicit Intent to a non-exported, same-process service. */
public final class CameraOperationDelivery extends Binder {
  private static final String EXTRA_DELIVERY = "camera_operation_delivery";

  public record Request(CameraImportGate.Admission admission, Object payload) {}

  private final ComponentName service;
  private final AtomicReference<Request> pending;
  private final AtomicBoolean submitted = new AtomicBoolean();

  private CameraOperationDelivery(ComponentName service, Request request) {
    this.service = service;
    pending = new AtomicReference<>(request);
  }

  /** Moves admission into a one-shot delivery. A consumed owner cannot create another delivery. */
  public static CameraOperationDelivery prepare(
      Intent intent, CameraImportGate.Admission admission, Object payload) {
    var owner = admission.handoff();
    if (owner == null) return null;
    try {
      return new CameraOperationDelivery(
          Objects.requireNonNull(intent.getComponent()), new Request(owner, payload));
    } catch (RuntimeException | Error failure) {
      owner.close();
      throw failure;
    }
  }

  public static boolean submit(
      Context context, Intent intent, CameraImportGate.Admission admission, Object payload) {
    var delivery = prepare(intent, admission, payload);
    return delivery != null && delivery.start(context, intent);
  }

  public boolean start(Context context, Intent intent) {
    if (!submitted.compareAndSet(false, true) || pending.get() == null) return false;
    try {
      if (!service.equals(intent.getComponent()))
        throw new IllegalArgumentException("Wrong service");
      Bundle extras = new Bundle();
      extras.putBinder(EXTRA_DELIVERY, this);
      intent.putExtras(extras);
      if (context.startForegroundService(intent) != null) return true;
    } catch (RuntimeException | Error failure) {
      discardPending();
      throw failure;
    }
    discardPending();
    return false;
  }

  public static Request claim(Intent intent, ComponentName receiver) {
    if (intent == null || !receiver.equals(intent.getComponent())) return null;
    try {
      Bundle extras = intent.getExtras();
      if (extras != null
          && extras.getBinder(EXTRA_DELIVERY) instanceof CameraOperationDelivery delivery
          && receiver.equals(delivery.service)) {
        Request request = delivery.pending.getAndSet(null);
        if (request != null && request.admission().isOpen()) return request;
      }
    } catch (BadParcelableException | ClassCastException malformed) {
      return null;
    }
    return null;
  }

  /** Cancels an unclaimed delivery. An already claimed request owns its own cleanup. */
  public boolean discardPending() {
    Request request = pending.getAndSet(null);
    if (request == null) return false;
    request.admission().close();
    return true;
  }
}
