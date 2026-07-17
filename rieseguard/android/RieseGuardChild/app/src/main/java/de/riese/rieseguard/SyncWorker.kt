package de.riese.rieseguard

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
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
            // Ensure the persistent foreground service is active
            try {
                val serviceIntent = android.content.Intent(applicationContext, RieseGuardService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(serviceIntent)
                } else {
                    applicationContext.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e("SyncWorker", "Error restarting service from worker", e)
            }

            val pm = applicationContext.packageManager
            
            // 1. Get GPS coordinates
            val location = getLastKnownLocation(applicationContext)
            val lat = location?.first
            val lng = location?.second
            Log.i("SyncWorker", "Location fetched: lat=$lat, lng=$lng")

            // 2. Scan installed launcher apps
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

            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            }
            val flags = android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES or
                        android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS
            val resolveInfos = pm.queryIntentActivities(intent, flags)

            val apps = resolveInfos.mapNotNull { info ->
                val packageName = info.activityInfo.packageName
                if (protectedPackages.contains(packageName)) return@mapNotNull null
                val appName = info.loadLabel(pm).toString()
                val usageMins = getTodayAppUsageMinutes(applicationContext, packageName)
                AppInfo(packageName, appName, usageMins)
            }.distinctBy { it.packageName }

            // 3. Upload apps and fetch policy (with GPS location coords)
            repository.uploadInstalledApps(serverUrl, deviceId, token, apps)
            val response = repository.getPolicy(serverUrl, deviceId, token, lat, lng)
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
                    
                    // Apply Private DNS Web Filter
                    policyController.setPrivateDns(response.webFilterActive)

                    // Apply School Mode
                    var isSchoolModeActive = false
                    if (response.schoolActive && response.schoolStart != null && response.schoolEnd != null) {
                        if (isTimeInSchedule(response.schoolStart, response.schoolEnd)) {
                            isSchoolModeActive = true
                        }
                    }
                    policyController.setSchoolMode(isSchoolModeActive)

                    val blockedPackages = (response.blockedPackages ?: emptyList()).toSet()
                    val isSettingsBlocked = blockedPackages.contains("com.android.settings")
                    sharedPrefs.edit().putBoolean("settings_blocked", isSettingsBlocked).apply()

                    // Apply Settings blocker
                    policyController.setApplicationHidden("com.android.settings", isSettingsBlocked)

                    // Parse and map app limits
                    val limitMap = response.appLimits?.associate { it.packageName to it.limitMinutes } ?: emptyMap()
                    val usageGranted = hasUsageStatsPermission(applicationContext)

                    apps.forEach { app ->
                        val pkg = app.packageName
                        var shouldBlock = blockedPackages.contains(pkg)

                        // If not blocked globally, check app time limits
                        if (!shouldBlock && usageGranted && limitMap.containsKey(pkg)) {
                            val limitMins = limitMap[pkg] ?: 0
                            val usageMins = getTodayAppUsageMinutes(applicationContext, pkg)
                            Log.d("SyncWorker", "App $pkg usage: $usageMins / $limitMins minutes today")
                            if (usageMins >= limitMins) {
                                shouldBlock = true
                                Log.i("SyncWorker", "App $pkg blocked: Limit reached ($usageMins >= $limitMins min)")
                            }
                        }

                        policyController.setApplicationHidden(pkg, shouldBlock)
                    }

                    // 4. OTA Update Check & silent installation
                    val latestVer = response.latestApkVersion ?: 0
                    val latestUrl = response.latestApkUrl ?: ""
                    
                    val currentVersionCode = try {
                        pm.getPackageInfo(applicationContext.packageName, 0).versionCode
                    } catch (e: Exception) {
                        1
                    }

                    if (latestVer > currentVersionCode && latestUrl.isNotEmpty()) {
                        Log.i("SyncWorker", "New APK version found: $latestVer (current: $currentVersionCode). Downloading...")
                        val destFile = File(applicationContext.cacheDir, "rieseguard-update.apk")
                        if (repository.downloadApk(serverUrl, latestUrl, destFile)) {
                            Log.i("SyncWorker", "APK downloaded. Initiating silent installation...")
                            val success = installApk(applicationContext, destFile)
                            if (success) {
                                Log.i("SyncWorker", "Silent update install initiated successfully.")
                            } else {
                                Log.e("SyncWorker", "Failed to start silent installation session.")
                            }
                        }
                    }
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

    private fun getLastKnownLocation(context: Context): Pair<Double, Double>? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager ?: return null
        val hasFine = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasCoarse = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        
        if (!hasFine && !hasCoarse) return null
        
        var location: android.location.Location? = null
        try {
            if (lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
                location = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
            }
            if (location == null && lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
                location = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            }
            if (location == null && lm.isProviderEnabled(android.location.LocationManager.PASSIVE_PROVIDER)) {
                location = lm.getLastKnownLocation(android.location.LocationManager.PASSIVE_PROVIDER)
            }
        } catch (e: SecurityException) {
            Log.e("SyncWorker", "Location permissions revoked or security exception", e)
        }
        
        return location?.let { Pair(it.latitude, it.longitude) }
    }

    private fun getTodayAppUsageMinutes(context: Context, packageName: String): Int {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager ?: return 0
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startTimeMidnight = calendar.timeInMillis
        val endTime = System.currentTimeMillis()
        
        // Method A: queryAndAggregateUsageStats
        var msMethodA = 0L
        try {
            val agg = usm.queryAndAggregateUsageStats(startTimeMidnight, endTime)
            val stat = agg?.get(packageName)
            if (stat != null) {
                msMethodA = stat.totalTimeInForeground
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    msMethodA = Math.max(msMethodA, stat.totalTimeVisible)
                }
            }
        } catch (e: Exception) {
            Log.w("SyncWorker", "Method A failed", e)
        }
        
        // Method B: queryUsageStats INTERVAL_DAILY
        var msMethodB = 0L
        try {
            val daily = usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, startTimeMidnight, endTime) ?: emptyList()
            for (s in daily) {
                if (s.packageName == packageName) {
                    var fg = s.totalTimeInForeground
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        fg = Math.max(fg, s.totalTimeVisible)
                    }
                    msMethodB += fg
                }
            }
        } catch (e: Exception) {
            Log.w("SyncWorker", "Method B failed", e)
        }
        
        // Method C: queryEvents and manually sum foreground time
        var msMethodC = 0L
        try {
            val events = usm.queryEvents(startTimeMidnight, endTime)
            if (events != null) {
                val event = android.app.usage.UsageEvents.Event()
                var lastResumeTime = 0L
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.packageName == packageName) {
                        val type = event.eventType
                        if (type == 1 || type == 19) {
                            lastResumeTime = event.timeStamp
                        } else if (type == 2 || type == 20) {
                            if (lastResumeTime > 0L) {
                                msMethodC += event.timeStamp - lastResumeTime
                                lastResumeTime = 0L
                            }
                        }
                    }
                }
                if (lastResumeTime > 0L) {
                    msMethodC += endTime - lastResumeTime
                }
            }
        } catch (e: Exception) {
            Log.w("SyncWorker", "Method C failed", e)
        }
        
        // Method D: Persisted in-memory usage
        val sharedPrefs = context.getSharedPreferences("RieseGuardPrefs", Context.MODE_PRIVATE)
        val msMethodD = sharedPrefs.getLong("usage_ms_$packageName", 0L)
        
        val bestMs = maxOf(msMethodA, msMethodB, msMethodC, msMethodD)
        
        // Save the best value to SharedPreferences so it never decreases during the day
        if (bestMs > msMethodD) {
            sharedPrefs.edit().putLong("usage_ms_$packageName", bestMs).apply()
        }
        
        return (bestMs / 60000).toInt()
    }

    private fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager ?: return false
        val mode = appOps.noteOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    private fun installApk(context: Context, apkFile: File): Boolean {
        val packageInstaller = context.packageManager.packageInstaller
        val params = android.content.pm.PackageInstaller.SessionParams(
            android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        var sessionId = -1
        var session: android.content.pm.PackageInstaller.Session? = null
        try {
            sessionId = packageInstaller.createSession(params)
            session = packageInstaller.openSession(sessionId)
            
            apkFile.inputStream().use { inputStream ->
                session.openWrite("RieseGuardUpdate", 0, -1).use { outputStream ->
                    inputStream.copyTo(outputStream)
                    session.fsync(outputStream)
                }
            }
            
            val intent = android.content.Intent(context, RieseDeviceAdminReceiver::class.java).apply {
                action = "de.riese.rieseguard.ACTION_INSTALL_COMMIT"
            }
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            )
            
            session.commit(pendingIntent.intentSender)
            return true
        } catch (e: Exception) {
            Log.e("SyncWorker", "Error installing package", e)
            if (sessionId != -1) {
                try {
                    packageInstaller.abandonSession(sessionId)
                } catch (_: Exception) {}
            }
            return false
        } finally {
            session?.close()
        }
    }
}
