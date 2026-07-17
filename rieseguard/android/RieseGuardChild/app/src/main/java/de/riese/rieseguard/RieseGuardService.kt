package de.riese.rieseguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar

class RieseGuardService : Service() {
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val repository = PolicyRepository()
    private lateinit var policyController: DevicePolicyController

    // In-memory foreground tracking (UsageStatsManager on LineageOS doesn't track active sessions live)
    private val inMemoryUsageMs = mutableMapOf<String, Long>()
    private var lastForegroundPkg: String? = null
    private var lastCheckTime: Long = System.currentTimeMillis()
    private var trackingDate: Int = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)

    override fun onCreate() {
        super.onCreate()
        policyController = DevicePolicyController(this)
        startForegroundService()
        
        // Load persisted usage stats
        val sharedPrefs = getSharedPreferences("RieseGuardPrefs", Context.MODE_PRIVATE)
        trackingDate = sharedPrefs.getInt("tracking_date", Calendar.getInstance().get(Calendar.DAY_OF_YEAR))
        sharedPrefs.all.forEach { (k, v) ->
            if (k.startsWith("usage_ms_")) {
                val pkg = k.substring("usage_ms_".length)
                val value = (v as? Number)?.toLong() ?: 0L
                inMemoryUsageMs[pkg] = value
            }
        }
        
        // Start the polling loop
        serviceScope.launch {
            while (isActive) {
                try {
                    // Track foreground app usage in-memory
                    updateForegroundTracking()
                    performSync()
                } catch (e: Exception) {
                    Log.e("RieseGuardService", "Error during background sync", e)
                }
                delay(30000) // Poll every 30 seconds
            }
        }
    }

    private fun updateForegroundTracking() {
        val now = System.currentTimeMillis()
        val sharedPrefs = getSharedPreferences("RieseGuardPrefs", Context.MODE_PRIVATE)
        
        // Reset tracking at midnight
        val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        if (today != trackingDate) {
            Log.i("RieseGuardService", "New day detected, resetting usage tracking")
            inMemoryUsageMs.clear()
            val editor = sharedPrefs.edit()
            sharedPrefs.all.keys.filter { it.startsWith("usage_ms_") }.forEach { editor.remove(it) }
            editor.putInt("tracking_date", today).apply()
            trackingDate = today
            lastForegroundPkg = null
        }
        
        // Check if screen is on/interactive
        val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val isInteractive = pm?.isInteractive ?: true
        if (!isInteractive) {
            lastForegroundPkg = null
            lastCheckTime = now
            return
        }
        
        // Get current foreground app
        val currentFg = getCurrentForegroundPackage()
        
        // If we had a foreground app last time, add elapsed time
        if (lastForegroundPkg != null && lastForegroundPkg != packageName) {
            val elapsed = now - lastCheckTime
            if (elapsed > 0 && elapsed < 120000) { // Sanity: max 2 minutes per interval
                val current = inMemoryUsageMs.getOrDefault(lastForegroundPkg!!, 0L)
                val newValue = current + elapsed
                inMemoryUsageMs[lastForegroundPkg!!] = newValue
                sharedPrefs.edit().putLong("usage_ms_${lastForegroundPkg}", newValue).apply()
                Log.d("RieseGuardService", "Tracked ${elapsed}ms for ${lastForegroundPkg}, total=${newValue}ms")
            }
        }
        
        lastForegroundPkg = currentFg
        lastCheckTime = now
    }

    private fun getCurrentForegroundPackage(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager ?: return null
        val now = System.currentTimeMillis()
        
        // Use queryUsageStats to find the app with the most recent lastTimeUsed in the last 10 minutes
        val stats = usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, now - 10 * 60 * 1000, now)
        Log.d("RieseGuardService", "getCurrentForegroundPackage: stats size=${stats?.size ?: 0}")
        if (stats.isNullOrEmpty()) {
            Log.d("RieseGuardService", "getCurrentForegroundPackage: stats is empty, returning lastForegroundPkg=$lastForegroundPkg")
            return lastForegroundPkg
        }
        
        // Find the app with the most recent lastTimeUsed timestamp
        var latestPkg: String? = null
        var latestTime = 0L
        for (s in stats) {
            if (s.lastTimeUsed > latestTime && s.packageName != packageName) {
                latestTime = s.lastTimeUsed
                latestPkg = s.packageName
            }
        }
        
        // Only count as foreground if lastTimeUsed is within the last 90 seconds (longer threshold for polling cycles)
        if (latestPkg != null && (now - latestTime) < 90000) {
            Log.d("RieseGuardService", "Foreground detected: $latestPkg (lastUsed ${(now - latestTime)/1000}s ago)")
            return latestPkg
        }
        
        Log.d("RieseGuardService", "getCurrentForegroundPackage: No app used in last 90s. Latest was $latestPkg (lastUsed ${(now - latestTime)/1000}s ago), returning null")
        return null
    }

    private suspend fun performSync() {
        val sharedPrefs = getSharedPreferences("RieseGuardPrefs", Context.MODE_PRIVATE)
        val serverUrl = sharedPrefs.getString("server_url", "") ?: ""
        val deviceId = sharedPrefs.getInt("device_id", -1)
        val token = sharedPrefs.getString("device_token", "") ?: ""

        if (serverUrl.isEmpty() || deviceId == -1 || token.isEmpty()) {
            return
        }

        // Scan installed apps and upload their current usage in real-time (every 30 seconds)
        try {
            val pm = packageManager
            val defaultLauncher = pm.resolveActivity(
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                },
                PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo?.packageName

            val protectedPackages = listOf(
                packageName,
                "com.android.systemui",
                "com.google.android.gms",
                "com.google.android.apps.nexuslauncher",
                "com.android.launcher",
                "com.android.launcher3",
                "android",
                defaultLauncher
            ).filterNotNull().toSet()

            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES or
                        PackageManager.MATCH_DISABLED_COMPONENTS
            val resolveInfos = pm.queryIntentActivities(intent, flags)

            val apps = resolveInfos.mapNotNull { info ->
                val pkgName = info.activityInfo.packageName
                if (protectedPackages.contains(pkgName)) return@mapNotNull null
                val appName = info.loadLabel(pm).toString()
                val usageMins = getTodayAppUsageMinutes(this, pkgName)
                AppInfo(pkgName, appName, usageMins)
            }.distinctBy { it.packageName }

            repository.uploadInstalledApps(serverUrl, deviceId, token, apps)
        } catch (e: Exception) {
            Log.e("RieseGuardService", "Error uploading apps in loop", e)
        }

        // Fast policy check
        val response = repository.getPolicy(serverUrl, deviceId, token, null, null)
        if (response != null) {
            var isLocked = response.locked
            var lockReason = response.lockReason ?: "Das Gerät wurde gesperrt."

            if (response.scheduleActive && response.scheduleStart != null && response.scheduleEnd != null) {
                if (isTimeInSchedule(response.scheduleStart, response.scheduleEnd)) {
                    isLocked = true
                    lockReason = "Schlafenszeit: Das Gerät ist bis ${response.scheduleEnd} gesperrt."
                }
            }
            
            val currentLocked = sharedPrefs.getBoolean("is_locked", false)
            if (isLocked != currentLocked) {
                Log.i("RieseGuardService", "Lock status changed: $isLocked")
                sharedPrefs.edit()
                    .putBoolean("is_locked", isLocked)
                    .putString("lock_reason", lockReason)
                    .apply()
                
                // If the device just got locked, launch MainActivity immediately to display the lock overlay
                if (isLocked) {
                    val intent = Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    startActivity(intent)
                }
            }

            if (policyController.isDeviceOwner()) {
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

                // Apply Settings blocker
                val blockedPackages = (response.blockedPackages ?: emptyList()).toSet()
                val isSettingsBlocked = blockedPackages.contains("com.android.settings")
                val currentSettingsBlocked = sharedPrefs.getBoolean("settings_blocked", false)
                if (isSettingsBlocked != currentSettingsBlocked) {
                    sharedPrefs.edit().putBoolean("settings_blocked", isSettingsBlocked).apply()
                    policyController.setApplicationHidden("com.android.settings", isSettingsBlocked)
                }

                // Apply all app block policies and app limits in real-time (every 5 seconds)
                val limitMap = response.appLimits?.associate { it.packageName to it.limitMinutes } ?: emptyMap()
                val usageGranted = hasUsageStatsPermission(this@RieseGuardService)
                Log.d("RieseGuardService", "App limit check: usageGranted=$usageGranted, limitMap=$limitMap")
                
                // Get set of packages to hide
                val toHide = mutableSetOf<String>()
                
                // 1. Add globally blocked packages
                toHide.addAll(blockedPackages)
                
                // 2. Add packages that reached their limit
                if (usageGranted) {
                    limitMap.forEach { (pkg, limitMins) ->
                        val usageMins = getTodayAppUsageMinutes(this@RieseGuardService, pkg)
                        Log.d("RieseGuardService", "Checking limit for $pkg: usage=$usageMins min, limit=$limitMins min")
                        if (usageMins >= limitMins) {
                            toHide.add(pkg)
                            Log.i("RieseGuardService", "App $pkg marked for block: limit reached")
                        }
                    }
                }

                // Load previously hidden packages to know what to unhide
                val previouslyHidden = sharedPrefs.getStringSet("previously_hidden_packages", emptySet()) ?: emptySet()
                Log.d("RieseGuardService", "toHide=$toHide, previouslyHidden=$previouslyHidden")
                
                // Unhide apps that are no longer hidden
                val toUnhide = previouslyHidden - toHide
                toUnhide.forEach { pkg ->
                    val ok = policyController.setApplicationHidden(pkg, false)
                    Log.d("RieseGuardService", "Unhiding app $pkg: success=$ok")
                }
                
                // Hide apps that need to be hidden
                toHide.forEach { pkg ->
                    val ok = policyController.setApplicationHidden(pkg, true)
                    Log.d("RieseGuardService", "Hiding app $pkg: success=$ok")
                }
                
                // Save new hidden set
                sharedPrefs.edit().putStringSet("previously_hidden_packages", toHide).apply()
            }
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

    private fun startForegroundService() {
        val channelId = "rieseguard_service_channel"
        val channelName = "RieseGuard Hintergrund Schutz"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(chan)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setOngoing(true)
            .setContentTitle("RieseGuard Aktiv")
            .setContentText("Das Gerät wird im Hintergrund geschützt.")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                101, 
                notification, 
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(101, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
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
        
        // Method A: queryAndAggregateUsageStats (most reliable on stock Android)
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
            Log.w("RieseGuardService", "Method A failed", e)
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
            Log.w("RieseGuardService", "Method B failed", e)
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
                        // ACTIVITY_RESUMED (19) or MOVE_TO_FOREGROUND (1)
                        if (type == 1 || type == 19) {
                            lastResumeTime = event.timeStamp
                        }
                        // ACTIVITY_PAUSED (20) or MOVE_TO_BACKGROUND (2)
                        else if (type == 2 || type == 20) {
                            if (lastResumeTime > 0L) {
                                msMethodC += event.timeStamp - lastResumeTime
                                lastResumeTime = 0L
                            }
                        }
                    }
                }
                // If app is still in foreground, count time until now
                if (lastResumeTime > 0L) {
                    msMethodC += endTime - lastResumeTime
                }
            }
        } catch (e: Exception) {
            Log.w("RieseGuardService", "Method C failed", e)
        }
        
        // Method D: In-memory foreground tracking (most reliable for active sessions)
        val msMethodD = inMemoryUsageMs.getOrDefault(packageName, 0L)
        
        val bestMs = maxOf(msMethodA, msMethodB, msMethodC, msMethodD)
        
        // Save the best value to inMemoryUsageMs and SharedPreferences so it never decreases during the day
        if (bestMs > msMethodD) {
            inMemoryUsageMs[packageName] = bestMs
            context.getSharedPreferences("RieseGuardPrefs", Context.MODE_PRIVATE)
                .edit().putLong("usage_ms_$packageName", bestMs).apply()
        }
        
        val resultMin = (bestMs / 60000).toInt()
        Log.d("RieseGuardService", "Usage for $packageName: A=${msMethodA}ms B=${msMethodB}ms C=${msMethodC}ms D=${msMethodD}ms => best=${bestMs}ms = ${resultMin}min")
        return resultMin
    }

    private fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager ?: return false
        val mode = appOps.checkOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }
}
