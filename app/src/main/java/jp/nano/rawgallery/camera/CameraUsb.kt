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

object CameraUsb {
    @JvmStatic fun knownCamera(device: UsbDevice): Boolean =
        device.vendorId == 0x07B4 || device.vendorId == 0x04DA

    @JvmStatic fun supported(device: UsbDevice): Boolean =
        knownCamera(device) || UsbMassStorage.isMassStorage(device)

    @JvmStatic fun attached(context: Context, intent: Intent): UsbDevice? {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return null
        val claimed = runCatching { deviceExtra(intent) }.getOrNull() ?: return null
        return context.getSystemService(UsbManager::class.java).deviceList[claimed.deviceName]
            ?.takeIf { knownCamera(it) && it.deviceId == claimed.deviceId }
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
        context: Context, device: UsbDevice, callback: java.util.function.Consumer<Boolean>,
    ): Closeable {
        val manager = context.getSystemService(UsbManager::class.java)
        val action = context.packageName + ".CAMERA_USB_PERMISSION." + UUID.randomUUID()
        val intent = Intent(action).setPackage(context.packageName)
        val pending = PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ONE_SHOT)
        var open = false
        lateinit var receiver: BroadcastReceiver
        val registration = Closeable {
            pending.cancel()
            if (open) {
                open = false
                context.unregisterReceiver(receiver)
            }
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, result: Intent) {
                if (result.action != action) return
                registration.close()
                val actual = manager.deviceList[device.deviceName]
                callback.accept(actual != null && actual.deviceId == device.deviceId &&
                    supported(actual) && manager.hasPermission(actual) &&
                    result.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, IntentFilter(action))
            }
            open = true
            manager.requestPermission(device, pending)
            return registration
        } catch (error: RuntimeException) {
            registration.close()
            throw error
        }
    }
}
