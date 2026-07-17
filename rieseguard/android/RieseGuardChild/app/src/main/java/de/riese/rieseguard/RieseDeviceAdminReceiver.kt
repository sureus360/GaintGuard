package de.riese.rieseguard

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class RieseDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(context, "RieseGuard Device Admin Enabled", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(context, "RieseGuard Device Admin Disabled", Toast.LENGTH_SHORT).show()
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "de.riese.rieseguard.ACTION_INSTALL_COMMIT") {
            val status = intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, android.content.pm.PackageInstaller.STATUS_FAILURE)
            val msg = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE)
            android.util.Log.i("RieseDeviceAdminReceiver", "OTA Update finish: status=$status, msg=$msg")
            if (status == android.content.pm.PackageInstaller.STATUS_SUCCESS) {
                Toast.makeText(context, "RieseGuard Update erfolgreich installiert!", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "RieseGuard Update fehlgeschlagen: $msg", Toast.LENGTH_LONG).show()
            }
        } else {
            super.onReceive(context, intent)
        }
    }
}
