package jp.nano.rawgallery.camera

import android.app.Activity
import android.content.Intent
import jp.nano.rawgallery.CameraImportActivity
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.widget.Toast
import jp.nano.rawgallery.R
import jp.nano.rawgallery.PhotoStorage
import java.io.Closeable

/** Transparent receiver for the Android USB handler; never trusts parcelled device details. */
class CameraAttachActivity : Activity() {
    companion object {
        const val ACTION_OPEN_FROM_USB_ATTACH = "jp.nano.rawgallery.OPEN_IMPORT_FROM_USB"
    }

    private var permission: Closeable? = null
    private var expectedVolume: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val device = CameraUsb.attached(this, intent)
        if (device == null) {
            finish()
            return
        }
        if (device.vendorId == SdRemountUsbPort.VENDOR && device.productId == SdRemountUsbPort.PRODUCT
            && SdRemountMountNotice.suppressesReconnectNotices()) {
            finish()
            return
        }
        val volume = savedInstanceState?.getString("expected_volume") ?: PhotoStorage.selected(this)
        expectedVolume = volume
        val settings = getSharedPreferences("camera_import", MODE_PRIVATE)
        val route = CameraCardRouting.route(UsbMassStorage.isMassStorage(device),
            CameraUsb.knownCamera(device), volume != PhotoStorage.PRIMARY)
        val attach = CameraCardRouting.onAttach(route,
            settings.getBoolean("configured", false) && settings.getBoolean("auto", true),
            settings.getBoolean("sd_auto", false) && CameraDifferentialService.isWatchingSdMounts())
        if (attach == CameraCardRouting.Attach.CONFIRM_MOUNTED) {
            startActivity(CameraImportActivity.mountedReaderAttachIntent(this, volume))
            finish()
            return
        }
        startActivity(Intent(this, CameraImportActivity::class.java).setAction(ACTION_OPEN_FROM_USB_ATTACH))
        if (attach == CameraCardRouting.Attach.OPEN_SCREEN) {
            finish()
            return
        }
        if (getSystemService(UsbManager::class.java).hasPermission(device)) {
            ImportService.start(this, device, expectedVolume = volume)
            finish()
        } else {
            permission = CameraUsb.request(this, device) { granted ->
                if (granted) ImportService.start(this, device, expectedVolume = volume)
                else Toast.makeText(this, R.string.camera_import_usb_permission_denied,
                    Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("expected_volume", expectedVolume)
    }

    override fun onDestroy() {
        permission?.close()
        super.onDestroy()
    }
}
