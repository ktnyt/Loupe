package jp.nano.rawgallery.camera;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.BadParcelableException;
import android.os.Binder;
import android.os.Bundle;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** One-shot admission carried by an explicit Intent to a non-exported, same-process service. */
public final class CameraOperationDelivery extends Binder {
  private static final String EXTRA_DELIVERY = "camera_operation_delivery";

  public record Request(CameraImportGate.Admission admission, Object payload) {}

  private final ComponentName service;
  private final AtomicReference<Request> pending;

  private CameraOperationDelivery(ComponentName service, Request request) {
    this.service = service;
    pending = new AtomicReference<>(request);
  }

  /** Takes ownership of admission, including when Android rejects the service start. */
  public static boolean submit(
      Context context, Intent intent, CameraImportGate.Admission admission, Object payload) {
    if (!admission.isOpen()) return false;
    var delivery =
        new CameraOperationDelivery(intent.getComponent(), new Request(admission, payload));
    try {
      Objects.requireNonNull(delivery.service);
      Bundle extras = new Bundle();
      extras.putBinder(EXTRA_DELIVERY, delivery);
      intent.putExtras(extras);
      if (context.startForegroundService(intent) != null) return true;
    } catch (RuntimeException | Error failure) {
      delivery.discardPending();
      throw failure;
    }
    delivery.discardPending();
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

  private void discardPending() {
    Request request = pending.getAndSet(null);
    if (request != null) request.admission().close();
  }
}
