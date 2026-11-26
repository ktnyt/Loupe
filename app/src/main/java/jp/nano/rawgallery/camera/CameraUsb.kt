package jp.nano.rawgallery.camera

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

object CameraUsb {
    internal interface Queries {
        fun devices(context: Context): Map<String, UsbDevice>

        fun hasPermission(
            context: Context,
            device: UsbDevice,
        ): Boolean

        fun requestPermission(
            context: Context,
            device: UsbDevice,
            pending: PendingIntent,
        )
    }

    @Volatile
    private var queries: Queries =
        object : Queries {
            private fun manager(context: Context): UsbManager =
                checkNotNull(
                    context.getSystemService(UsbManager::class.java),
                )

            override fun devices(context: Context): Map<String, UsbDevice> = manager(context).deviceList

            override fun hasPermission(
                context: Context,
                device: UsbDevice,
            ): Boolean = manager(context).hasPermission(device)

            override fun requestPermission(
                context: Context,
                device: UsbDevice,
                pending: PendingIntent,
            ) {
                manager(context).requestPermission(device, pending)
            }
        }

    @JvmStatic fun devices(context: Context): Map<String, UsbDevice> = queries.devices(context)

    @JvmStatic fun hasPermission(
        context: Context,
        device: UsbDevice,
    ): Boolean = queries.hasPermission(context, device)

    @JvmStatic fun knownCamera(device: UsbDevice): Boolean = device.vendorId == 0x07B4 || device.vendorId == 0x04DA

    @JvmStatic fun supported(device: UsbDevice): Boolean = knownCamera(device) || UsbMassStorage.isMassStorage(device)

    @JvmStatic fun attached(
        context: Context,
        intent: Intent,
    ): UsbDevice? {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return null
        val claimed = runCatching { deviceExtra(intent) }.getOrNull() ?: return null
        return try {
            devices(context)[claimed.deviceName]?.takeIf { supported(it) && it.deviceId == claimed.deviceId }
        } catch (_: RuntimeException) {
            null
        }
    }

    @Suppress("DEPRECATION")
    internal fun deviceExtra(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    /** The callback only authorizes the exact device still present in UsbManager. */
    @JvmStatic fun request(
        context: Context,
        device: UsbDevice,
        callback: java.util.function.Consumer<Boolean>,
    ): Closeable {
        val action = context.packageName + ".CAMERA_USB_PERMISSION." + UUID.randomUUID()
        val intent = Intent(action).setPackage(context.packageName)
        val pending =
            PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ONE_SHOT,
            )
        val open = AtomicBoolean(false)
        lateinit var receiver: BroadcastReceiver

        fun closeReceiver() {
            try {
                context.unregisterReceiver(receiver)
            } finally {
                pending.cancel()
            }
        }
        val registration =
            Closeable {
                if (open.compareAndSet(true, false)) closeReceiver()
            }
        receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    receiverContext: Context,
                    result: Intent,
                ) {
                    if (result.action != action || !open.compareAndSet(true, false)) return
                    val granted =
                        try {
                            closeReceiver()
                            val actual = devices(context)[device.deviceName]
                            actual != null && actual.deviceId == device.deviceId &&
                                actual.deviceName == device.deviceName && actual.vendorId == device.vendorId &&
                                actual.productId == device.productId && supported(actual) &&
                                hasPermission(context, actual) &&
                                result.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        } catch (_: RuntimeException) {
                            false
                        }
                    callback.accept(granted)
                }
            }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, IntentFilter(action))
            }
            open.set(true)
            queries.requestPermission(context, device, pending)
            return registration
        } catch (error: RuntimeException) {
            try {
                if (open.get()) registration.close() else pending.cancel()
            } catch (cleanup: RuntimeException) {
                error.addSuppressed(cleanup)
            }
            throw error
        }
    }
}
