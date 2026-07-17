package de.riese.rieseguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class RelaunchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.i("RelaunchReceiver", "RieseGuard event received (${intent.action})! Rescheduling periodic sync and relaunching foreground service.")
            
            // Relaunch the persistent foreground service
            try {
                val serviceIntent = Intent(context, RieseGuardService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e("RelaunchReceiver", "Error relaunching foreground service", e)
            }

            try {
                val workManager = WorkManager.getInstance(context.applicationContext)
                val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                    .setInitialDelay(10, TimeUnit.SECONDS)
                    .build()
                workManager.enqueueUniquePeriodicWork("RieseSyncPeriodic", ExistingPeriodicWorkPolicy.KEEP, syncRequest)
            } catch (e: Exception) {
                Log.e("RelaunchReceiver", "Error rescheduling background work", e)
            }
        }
    }
}
