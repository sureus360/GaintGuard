package de.riese.rieseguard

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import android.util.Log
import android.widget.Toast
import java.util.concurrent.TimeUnit
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.activity.compose.rememberLauncherForActivityResult
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var policyController: DevicePolicyController
    private var preferenceChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private var isLocked by mutableStateOf(false)
    private var lastSync by mutableStateOf("Noch nie")
    private var lockReason by mutableStateOf("Das Gerät wurde gesperrt.")

    companion object {
        var isAuthenticated = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        policyController = DevicePolicyController(this)

        // Start the persistent real-time sync foreground service
        val serviceIntent = android.content.Intent(this, RieseGuardService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        setContent {
            val sharedPrefs = remember { getSharedPreferences("RieseGuardPrefs", Context.MODE_PRIVATE) }
            var authenticated by remember { mutableStateOf(isAuthenticated) }

            DisposableEffect(sharedPrefs) {
                isLocked = sharedPrefs.getBoolean("is_locked", false)
                lastSync = sharedPrefs.getString("last_sync", "Noch nie") ?: "Noch nie"
                lockReason = sharedPrefs.getString("lock_reason", "Das Gerät wurde gesperrt.") ?: "Das Gerät wurde gesperrt."

                if (isLocked) {
                    try {
                        policyController.setKioskModeEnabled(true)
                        startLockTask()
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error starting lock task on init", e)
                    }
                }

                val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
                    when (key) {
                        "is_locked" -> {
                            val locked = prefs.getBoolean("is_locked", false)
                            isLocked = locked
                            if (locked) {
                                try {
                                    policyController.setKioskModeEnabled(true)
                                    startLockTask()
                                } catch (e: Exception) {
                                    Log.e("MainActivity", "Error starting lock task", e)
                                }
                            } else {
                                try {
                                    stopLockTask()
                                    policyController.setKioskModeEnabled(false)
                                    finish()
                                } catch (e: Exception) {
                                    Log.e("MainActivity", "Error stopping lock task", e)
                                }
                            }
                        }
                        "last_sync" -> lastSync = prefs.getString("last_sync", "Noch nie") ?: "Noch nie"
                        "lock_reason" -> lockReason = prefs.getString("lock_reason", "Das Gerät wurde gesperrt.") ?: "Das Gerät wurde gesperrt."
                    }
                }
                preferenceChangeListener = listener
                sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
                onDispose {
                    sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener)
                }
            }

            LaunchedEffect(Unit) {
                val workManager = WorkManager.getInstance(applicationContext)
                val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                    .setInitialDelay(10, TimeUnit.SECONDS)
                    .build()
                workManager.enqueueUniquePeriodicWork("RieseSyncPeriodic", ExistingPeriodicWorkPolicy.KEEP, syncRequest)

                // Enqueue immediate one-time sync to update apps list and location right away
                val immediateSync = OneTimeWorkRequestBuilder<SyncWorker>().build()
                workManager.enqueueUniqueWork("RieseSyncImmediate", ExistingWorkPolicy.REPLACE, immediateSync)
            }

            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (isLocked) {
                        LockScreen(
                            reason = lockReason,
                            onForceSync = {
                                val workManager = WorkManager.getInstance(applicationContext)
                                val forceSync = OneTimeWorkRequestBuilder<SyncWorker>().build()
                                workManager.enqueueUniqueWork("RieseSyncManual", ExistingWorkPolicy.REPLACE, forceSync)
                            }
                        )
                    } else if (!authenticated) {
                        PinScreen(
                            onPinCorrect = {
                                isAuthenticated = true
                                authenticated = true
                            }
                        )
                    } else {
                        RieseGuardScreen(
                            context = this@MainActivity,
                            controller = policyController,
                            lastSync = lastSync,
                            isLocked = isLocked,
                            onLogout = {
                                isAuthenticated = false
                                authenticated = false
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::policyController.isInitialized) {
            val sharedPrefs = getSharedPreferences("RieseGuardPrefs", Context.MODE_PRIVATE)
            isLocked = sharedPrefs.getBoolean("is_locked", false)
            lastSync = sharedPrefs.getString("last_sync", "Noch nie") ?: "Noch nie"
            lockReason = sharedPrefs.getString("lock_reason", "Das Gerät wurde gesperrt.") ?: "Das Gerät wurde gesperrt."
            
            if (isLocked) {
                try {
                    policyController.setKioskModeEnabled(true)
                    startLockTask()
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error starting lock task onResume", e)
                }
            }

            if (sharedPrefs.getBoolean("settings_blocked", false)) {
                policyController.setApplicationHidden("com.android.settings", true)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Optional: Keep authentication during the session? 
        // User said unstable, maybe auto-logout is annoying. Let's keep it for now.
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isLocked) {
            val intent = android.content.Intent(this, MainActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && isLocked) {
            try {
                @Suppress("DEPRECATION")
                val closeRecents = android.content.Intent(android.content.Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
                sendBroadcast(closeRecents)
            } catch (_: Exception) {}

            val intent = android.content.Intent(this, MainActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
        }
    }
}

@Composable
fun LockScreen(reason: String, onForceSync: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF111218)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "RieseGuard",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = "Dieses Gerät ist gesperrt.",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier.padding(bottom = 24.dp)
            )
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Grund: $reason",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(20.dp),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onForceSync,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Sperrstatus aktualisieren")
            }
        }
    }
}

@Composable
fun PinScreen(onPinCorrect: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "RieseGuard Eltern-Bereich",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "Bitte gib deine Eltern-PIN ein.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        OutlinedTextField(
            value = pin,
            onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) pin = it },
            label = { Text("Eltern-PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        if (showError) {
            Text("Ungültige PIN.", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }

        Button(
            onClick = {
                if (pin == "1234") onPinCorrect() else showError = true
            },
            modifier = Modifier.padding(top = 24.dp).fillMaxWidth()
        ) {
            Text("Anmelden")
        }
    }
}

@Composable
fun RieseGuardScreen(
    context: Context,
    controller: DevicePolicyController,
    lastSync: String,
    isLocked: Boolean,
    onLogout: () -> Unit
) {
    val sharedPrefs = context.getSharedPreferences("RieseGuardPrefs", Context.MODE_PRIVATE)
    var serverUrl by remember { mutableStateOf(sharedPrefs.getString("server_url", "http://10.0.2.2:8088") ?: "") }
    var deviceIdStr by remember { mutableStateOf(sharedPrefs.getInt("device_id", 1).toString()) }
    var deviceToken by remember { mutableStateOf(sharedPrefs.getString("device_token", "") ?: "") }

    val scanLauncher = rememberLauncherForActivityResult(
        contract = ScanContract(),
        onResult = { result ->
            val rawValue = result.contents
            if (rawValue != null) {
                try {
                    val json = JSONObject(rawValue)
                    serverUrl = json.getString("url")
                    deviceIdStr = json.getInt("id").toString()
                    deviceToken = json.getString("token")
                    Toast.makeText(context, "QR-Code erfolgreich gescannt!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Ungültiger QR-Code Inhalt!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    )

    var isDeviceOwner by remember { mutableStateOf(controller.isDeviceOwner()) }
    var isDeviceAdmin by remember { mutableStateOf(controller.isDeviceAdmin()) }

    val workManager = remember { WorkManager.getInstance(context) }
    val manualSyncFlow = remember { workManager.getWorkInfosForUniqueWorkFlow("RieseSyncManual") }
    val syncWorkInfos by manualSyncFlow.collectAsState(initial = emptyList())
    
    var syncMessage by remember { mutableStateOf<String?>(null) }
    var isSyncing by remember { mutableStateOf(false) }

    LaunchedEffect(syncWorkInfos) {
        val workInfo = syncWorkInfos.firstOrNull()
        if (workInfo != null) {
            when (workInfo.state) {
                WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> {
                    isSyncing = true
                }
                WorkInfo.State.SUCCEEDED -> {
                    if (isSyncing) {
                        isSyncing = false
                        syncMessage = "Synchronisierung erfolgreich!"
                    }
                }
                WorkInfo.State.FAILED -> {
                    if (isSyncing) {
                        isSyncing = false
                        syncMessage = "Fehler bei der Synchronisierung!"
                    }
                }
                else -> {
                    isSyncing = false
                }
            }
        }
    }

    LaunchedEffect(syncMessage) {
        syncMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            syncMessage = null
        }
    }

    var locationGranted by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    var usageStatsGranted by remember {
        mutableStateOf(
            try {
                val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
                val mode = appOps.noteOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
                mode == android.app.AppOpsManager.MODE_ALLOWED
            } catch (e: Exception) {
                false
            }
        )
    }

    LaunchedEffect(Unit) {
        while(true) {
            isDeviceOwner = controller.isDeviceOwner()
            isDeviceAdmin = controller.isDeviceAdmin()
            locationGranted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            usageStatsGranted = try {
                val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
                val mode = appOps.noteOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
                mode == android.app.AppOpsManager.MODE_ALLOWED
            } catch (e: Exception) {
                false
            }
            kotlinx.coroutines.delay(5000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("RieseGuard Status", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Divider()

        if (!locationGranted) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("GPS-Berechtigung fehlt (für Standort-Ortung)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = {
                            if (context is Activity) {
                                androidx.core.app.ActivityCompat.requestPermissions(
                                    context,
                                    arrayOf(
                                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                                    ),
                                    101
                                )
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Berechtigung erteilen", fontSize = 11.sp)
                    }
                }
            }
        }
        
        if (!usageStatsGranted) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Nutzungsdatenzugriff fehlt (für App-Zeitlimits)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                                data = android.net.Uri.fromParts("package", context.packageName, null)
                            }
                            try {
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Einstellungen öffnen", fontSize = 11.sp)
                    }
                }
            }
        }

        
        Button(
            onClick = {
                val options = ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt("Kopplungs-QR-Code scannen")
                    setCameraId(0)
                    setBeepEnabled(false)
                    setBarcodeImageEnabled(false)
                    setOrientationLocked(false)
                }
                scanLauncher.launch(options)
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1))
        ) {
            Text("📷 QR-Code scannen")
        }

        OutlinedTextField(value = serverUrl, onValueChange = { serverUrl = it }, label = { Text("Server URL") }, modifier = Modifier.fillMaxWidth())
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = deviceIdStr, onValueChange = { deviceIdStr = it }, label = { Text("ID") }, modifier = Modifier.weight(1f))
            OutlinedTextField(value = deviceToken, onValueChange = { deviceToken = it }, label = { Text("Token") }, modifier = Modifier.weight(2f))
        }

        Button(
            onClick = {
                val parsedId = deviceIdStr.toIntOrNull()
                if (serverUrl.isBlank() || parsedId == null || parsedId <= 0 || deviceToken.isBlank()) {
                    Toast.makeText(context, "Bitte alle Felder korrekt ausfüllen!", Toast.LENGTH_SHORT).show()
                } else {
                    sharedPrefs.edit()
                        .putString("server_url", serverUrl)
                        .putInt("device_id", parsedId)
                        .putString("device_token", deviceToken)
                        .apply()
                    
                    val forceSync = OneTimeWorkRequestBuilder<SyncWorker>().build()
                    workManager.enqueueUniqueWork("RieseSyncManual", ExistingWorkPolicy.REPLACE, forceSync)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isSyncing) {
                Text("Sync läuft...")
            } else {
                Text("Speichern & Sync")
            }
        }

        Divider()

        StatusRow(label = "Owner Status:", value = if (isDeviceOwner) "AKTIV" else "INAKTIV", color = if (isDeviceOwner) Color(0xFF4CAF50) else Color.Red)
        StatusRow(label = "Admin Status:", value = if (isDeviceAdmin) "AKTIV" else "INAKTIV", color = if (isDeviceAdmin) Color(0xFF4CAF50) else Color.Red)
        StatusRow(label = "Letzter Sync:", value = lastSync, color = MaterialTheme.colorScheme.onBackground)
        StatusRow(label = "Sperrstatus:", value = if (isLocked) "GESPERRT" else "OFFEN", color = if (isLocked) Color.Red else Color(0xFF4CAF50))

        Spacer(modifier = Modifier.weight(1f))

        Button(onClick = onLogout, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
            Text("Ausloggen")
        }
        
        Button(
            onClick = {
                if (isDeviceOwner || isDeviceAdmin) controller.lockDevice() else controller.activateDeviceAdmin()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            Text(if (isDeviceOwner || isDeviceAdmin) "Test Sperre" else "Admin Aktivieren")
        }
    }
}

@Composable
fun StatusRow(label: String, value: String, color: Color) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, fontWeight = FontWeight.Medium)
        Text(text = value, color = color, fontWeight = FontWeight.Bold)
    }
}
