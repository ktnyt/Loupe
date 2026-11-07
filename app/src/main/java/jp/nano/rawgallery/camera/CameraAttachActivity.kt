package jp.nano.rawgallery.camera

import android.app.Activity
import android.content.Intent
import jp.nano.rawgallery.CameraImportActivity
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.widget.Toast
import jp.nano.rawgallery.R
import java.io.Closeable

/** Transparent receiver for the Android USB handler; never trusts parcelled device details. */
class CameraAttachActivity : Activity() {
    private var permission: Closeable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val device = CameraUsb.attached(this, intent)
        if (device == null) {
            finish()
            return
        }
        val settings = getSharedPreferences("camera_import", MODE_PRIVATE)
        if (!settings.getBoolean("configured", false)) {
            startActivity(Intent(this, CameraImportActivity::class.java))
            finish()
            return
        }
        if (!settings.getBoolean("auto", true)) {
            finish()
            return
        }
        if (getSystemService(UsbManager::class.java).hasPermission(device)) {
            ImportService.start(this, device)
            finish()
        } else {
            permission = CameraUsb.request(this, device) { granted ->
                if (granted) ImportService.start(this, device)
                else Toast.makeText(this, R.string.camera_import_usb_permission_denied,
                    Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        permission?.close()
        super.onDestroy()
    }
}
