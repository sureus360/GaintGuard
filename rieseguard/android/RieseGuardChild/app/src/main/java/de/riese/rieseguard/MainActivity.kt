package de.riese.rieseguard

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
import android.util.Log
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private lateinit var policyController: DevicePolicyController

    companion object {
        var isAuthenticated = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        policyController = DevicePolicyController(this)

        setContent {
            val sharedPrefs = remember { getSharedPreferences("RieseGuardPrefs", Context.MODE_PRIVATE) }
            var authenticated by remember { mutableStateOf(isAuthenticated) }
            
            var isLocked by remember { mutableStateOf(sharedPrefs.getBoolean("is_locked", false)) }
            var lastSync by remember { mutableStateOf(sharedPrefs.getString("last_sync", "Noch nie") ?: "Noch nie") }
            var lockReason by remember { mutableStateOf(sharedPrefs.getString("lock_reason", "Das Gerät wurde gesperrt.") ?: "Das Gerät wurde gesperrt.") }

            DisposableEffect(sharedPrefs) {
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
                    when (key) {
                        "is_locked" -> isLocked = prefs.getBoolean("is_locked", false)
                        "last_sync" -> lastSync = prefs.getString("last_sync", "Noch nie") ?: "Noch nie"
                        "lock_reason" -> lockReason = prefs.getString("lock_reason", "Das Gerät wurde gesperrt.") ?: "Das Gerät wurde gesperrt."
                    }
                }
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
            }

            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (isLocked) {
                        LockScreen(lockReason)
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
}

@Composable
fun LockScreen(reason: String) {
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

    var isDeviceOwner by remember { mutableStateOf(controller.isDeviceOwner()) }
    var isDeviceAdmin by remember { mutableStateOf(controller.isDeviceAdmin()) }

    LaunchedEffect(Unit) {
        while(true) {
            isDeviceOwner = controller.isDeviceOwner()
            isDeviceAdmin = controller.isDeviceAdmin()
            kotlinx.coroutines.delay(10000)
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
        
        OutlinedTextField(value = serverUrl, onValueChange = { serverUrl = it }, label = { Text("Server URL") }, modifier = Modifier.fillMaxWidth())
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = deviceIdStr, onValueChange = { deviceIdStr = it }, label = { Text("ID") }, modifier = Modifier.weight(1f))
            OutlinedTextField(value = deviceToken, onValueChange = { deviceToken = it }, label = { Text("Token") }, modifier = Modifier.weight(2f))
        }

        Button(
            onClick = {
                sharedPrefs.edit()
                    .putString("server_url", serverUrl)
                    .putInt("device_id", deviceIdStr.toIntOrNull() ?: -1)
                    .putString("device_token", deviceToken)
                    .apply()
                
                val workManager = WorkManager.getInstance(context)
                val forceSync = OneTimeWorkRequestBuilder<SyncWorker>().build()
                workManager.enqueueUniqueWork("RieseSyncManual", ExistingWorkPolicy.REPLACE, forceSync)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Speichern & Sync")
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
