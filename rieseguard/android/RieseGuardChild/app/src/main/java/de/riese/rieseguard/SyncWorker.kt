package de.riese.rieseguard

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SyncWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val repository = PolicyRepository()
    private val policyController = DevicePolicyController(appContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sharedPrefs = applicationContext.getSharedPreferences("RieseGuardPrefs", Context.MODE_PRIVATE)
        val serverUrl = sharedPrefs.getString("server_url", "") ?: ""
        val deviceId = sharedPrefs.getInt("device_id", -1)
        val token = sharedPrefs.getString("device_token", "") ?: ""

        if (serverUrl.isEmpty() || deviceId == -1 || token.isEmpty()) {
            Log.w("SyncWorker", "Sync skipped: Configuration incomplete")
            return@withContext Result.failure()
        }

        try {
            val pm = applicationContext.packageManager
            
            // 1. Scan installed launcher apps
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(intent, 0)
            
            val intentHome = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_HOME)
            }
            val resolveInfoHome = pm.resolveActivity(intentHome, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            val defaultLauncher = resolveInfoHome?.activityInfo?.packageName

            val protectedPackages = setOf(
                applicationContext.packageName,
                "com.android.systemui",
                "com.google.android.gms",
                "com.google.android.apps.nexuslauncher",
                "com.android.launcher",
                "com.android.launcher3",
                "android",
                defaultLauncher
            ).filterNotNull().toSet()

            val apps = resolveInfos.mapNotNull { info ->
                val packageName = info.activityInfo.packageName
                if (protectedPackages.contains(packageName)) return@mapNotNull null
                
                // loadLabel can be slow, but we are on IO thread
                val appName = info.loadLabel(pm).toString()
                AppInfo(packageName, appName)
            }.distinctBy { it.packageName }

            // 2. Upload and Fetch
            repository.uploadInstalledApps(serverUrl, deviceId, token, apps)
            val response = repository.getPolicy(serverUrl, deviceId, token)
            val currentTimestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            
            if (response != null) {
                var isLocked = response.locked
                var lockReason = response.lockReason ?: "Das Gerät wurde gesperrt."

                if (response.scheduleActive && response.scheduleStart != null && response.scheduleEnd != null) {
                    if (isTimeInSchedule(response.scheduleStart, response.scheduleEnd)) {
                        isLocked = true
                        lockReason = "Schlafenszeit: Das Gerät ist bis ${response.scheduleEnd} gesperrt."
                    }
                }

                sharedPrefs.edit()
                    .putBoolean("is_locked", isLocked)
                    .putString("lock_reason", lockReason)
                    .putString("last_sync", currentTimestamp)
                    .apply()

                if (isLocked) {
                    policyController.lockDevice()
                }

                if (policyController.isDeviceOwner()) {
                    policyController.applyAntiTamperingRestrictions(true)
                    val blockedPackages = (response.blockedPackages ?: emptyList()).toSet()

                    val isSettingsBlocked = blockedPackages.contains("com.android.settings")
                    sharedPrefs.edit().putBoolean("settings_blocked", isSettingsBlocked).apply()

                    val hideablePackages = setOf("com.android.settings", "com.android.vending")
                    
                    // Optimization: Batch process apps
                    val appsToSuspend = mutableListOf<String>()
                    val appsToUnsuspend = mutableListOf<String>()

                    apps.forEach { app ->
                        val pkg = app.packageName
                        val shouldBlock = blockedPackages.contains(pkg)
                        
                        if (hideablePackages.contains(pkg)) {
                            policyController.setApplicationHidden(pkg, shouldBlock)
                        } else {
                            if (shouldBlock) appsToSuspend.add(pkg) else appsToUnsuspend.add(pkg)
                        }
                    }
                    
                    if (appsToSuspend.isNotEmpty()) policyController.suspendPackages(appsToSuspend, true)
                    if (appsToUnsuspend.isNotEmpty()) policyController.suspendPackages(appsToUnsuspend, false)
                }
                
                Result.success()
            } else {
                Log.e("SyncWorker", "Null response received from server")
                Result.failure()
            }
        } catch (e: Exception) {
            Log.e("SyncWorker", "Error executing sync", e)
            Result.failure()
        }
    }

    private fun isTimeInSchedule(start: String, end: String): Boolean {
        return try {
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            val nowStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            val nowTime = sdf.parse(nowStr)
            val startTime = sdf.parse(start)
            val endTime = sdf.parse(end)

            if (nowTime == null || startTime == null || endTime == null) return false

            if (startTime.before(endTime)) {
                (nowTime.after(startTime) || nowTime == startTime) && nowTime.before(endTime)
            } else {
                nowTime.after(startTime) || nowTime == startTime || nowTime.before(endTime)
            }
        } catch (e: Exception) {
            false
        }
    }
}
