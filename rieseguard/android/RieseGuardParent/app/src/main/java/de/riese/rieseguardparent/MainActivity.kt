package de.riese.rieseguardparent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import android.os.Vibrator
import android.os.VibrationEffect
import android.os.Build
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Delete
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.compose.material3.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {
    private val repository = ParentRepository()

    fun showBiometricPrompt(onSuccess: () -> Unit) {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("RieseGuard entsperren")
            .setSubtitle("Nutze deinen Fingerabdruck zum Entsperren")
            .setNegativeButtonText("Abbrechen")
            .build()

        val biometricManager = BiometricManager.from(this)
        if (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS) {
            biometricPrompt.authenticate(promptInfo)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ParentAppTheme {
                MainScreen(repository)
            }
        }
    }
}

@Composable
fun ParentAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF6366F1),
            secondary = Color(0xFF4F46E5),
            background = Color(0xFF0F0F15),
            surface = Color(0xFF181824),
            error = Color(0xFFEF4444)
        ),
        content = content
    )
}

@Composable
fun MainScreen(repository: ParentRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sharedPrefs = remember { context.getSharedPreferences("RieseParentPrefs", Context.MODE_PRIVATE) }

    var serverUrl by remember { mutableStateOf(sharedPrefs.getString("server_url", "http://10.0.2.2:8088") ?: "") }
    var token by remember { mutableStateOf(sharedPrefs.getString("auth_token", "") ?: "") }
    var email by remember { mutableStateOf(sharedPrefs.getString("email", "") ?: "") }

    var isLoggedIn by remember { mutableStateOf(token.isNotEmpty()) }
    var currentScreen by remember { mutableStateOf(if (isLoggedIn) "devices" else "login") }
    var selectedDeviceForApps by remember { mutableStateOf<DeviceInfo?>(null) }
    var selectedDeviceForSchedule by remember { mutableStateOf<DeviceInfo?>(null) }
    var selectedDeviceForSchool by remember { mutableStateOf<DeviceInfo?>(null) }
    var showAddDeviceDialog by remember { mutableStateOf(false) }
    var pairingDevice by remember { mutableStateOf<DeviceInfo?>(null) }
    
    var appPin by remember { mutableStateOf(sharedPrefs.getString("app_pin", "") ?: "") }
    var useBiometric by remember { mutableStateOf(sharedPrefs.getBoolean("use_biometric", false)) }
    var isAppLocked by remember { mutableStateOf(appPin.isNotEmpty()) }
    var showSecuritySettings by remember { mutableStateOf(false) }
    var deviceToDelete by remember { mutableStateOf<DeviceInfo?>(null) }
    var showDeleteAccountConfirmation by remember { mutableStateOf(false) }

    var otaVersionCode by remember { mutableStateOf("2") }
    var otaVersionName by remember { mutableStateOf("1.1") }
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf("") }
    var uploadStatus by remember { mutableStateOf("") }

    LaunchedEffect(token) {
        repository.setToken(if (token.isEmpty()) null else token)
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        selectedFileUri = uri
        selectedFileName = uri?.lastPathSegment ?: ""
    }

    if (isAppLocked) {
        PinLockScreen(
            correctPin = appPin,
            useBiometric = useBiometric,
            onUnlock = { isAppLocked = false },
            onBiometricTrigger = {
                val activity = context as? MainActivity
                activity?.showBiometricPrompt {
                    isAppLocked = false
                }
            }
        )
    } else {
        if (currentScreen == "login") {
        LoginScreen(
            serverUrl = serverUrl,
            onServerUrlChange = { serverUrl = it },
            email = email,
            onEmailChange = { email = it },
            onLoginSuccess = { t, e ->
                token = t
                email = e
                sharedPrefs.edit()
                    .putString("auth_token", t)
                    .putString("email", e)
                    .putString("server_url", serverUrl)
                    .apply()
                isLoggedIn = true
                currentScreen = "devices"
            },
            repository = repository
        )
    } else {
        var devicesList by remember { mutableStateOf<List<DeviceInfo>>(emptyList()) }
        var isRefreshing by remember { mutableStateOf(false) }

        val refreshDevices = {
            scope.launch {
                isRefreshing = true
                devicesList = withContext(Dispatchers.IO) {
                    repository.listDevices(serverUrl)
                }
                isRefreshing = false
            }
        }

        LaunchedEffect(Unit) {
            refreshDevices()
        }

        Scaffold { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0F0F15))
                    .padding(padding)
            ) {
                if (currentScreen == "devices") {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Custom Top Bar
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "RieseGuard Eltern",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { showSecuritySettings = true }) {
                                    Icon(
                                        imageVector = Icons.Filled.Lock,
                                        contentDescription = "App-Schutz",
                                        tint = if (appPin.isNotEmpty()) Color(0xFF6366F1) else Color.White
                                    )
                                }
                                IconButton(onClick = { refreshDevices() }) {
                                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = Color.White)
                                }
                                Button(
                                    onClick = {
                                        token = ""
                                        email = ""
                                        sharedPrefs.edit().remove("auth_token").remove("email").apply()
                                        isLoggedIn = false
                                        currentScreen = "login"
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text("Abmelden", fontSize = 12.sp)
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Geräte", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { currentScreen = "ota" },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF333344),
                                        contentColor = Color.White
                                    )
                                ) {
                                    Text("OTA Updates")
                                }
                                Button(onClick = { showAddDeviceDialog = true }) {
                                    Icon(Icons.Filled.Add, "Hinzufügen")
                                    Spacer(Modifier.width(4.dp))
                                    Text("Gerät")
                                }
                            }
                        }

                        if (isRefreshing && devicesList.isEmpty()) {
                            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else if (devicesList.isEmpty()) {
                            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text("Keine Geräte registriert.", color = Color.Gray, textAlign = TextAlign.Center)
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(devicesList, key = { it.id }) { device ->
                                    DeviceCard(
                                        device = device,
                                        onLockUnlock = { lock, reason ->
                                            scope.launch {
                                                val ok = withContext(Dispatchers.IO) {
                                                    repository.toggleLock(serverUrl, device.id, lock, reason)
                                                }
                                                if (ok) refreshDevices()
                                                else Toast.makeText(context, "Fehler beim Sperrstatus-Ändern", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        onScheduleClick = { selectedDeviceForSchedule = device },
                                        onSchoolClick = { selectedDeviceForSchool = device },
                                        onWebFilterToggle = { active ->
                                            scope.launch {
                                                val ok = withContext(Dispatchers.IO) {
                                                    repository.toggleWebFilter(serverUrl, device.id, active)
                                                }
                                                if (ok) refreshDevices()
                                                else Toast.makeText(context, "Fehler beim Web-Filter-Umschalten", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                         onAppsClick = { selectedDeviceForApps = device },
                                         onDeleteClick = { deviceToDelete = device },
                                         onPairingClick = { pairingDevice = device }
                                     )
                                }
                            }
                        }
                    }
                } else if (currentScreen == "ota") {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("OTA App-Update hochladen", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(
                            "Wähle eine neue APK-Version für RieseGuardChild aus. Alle Geräte werden das Update automatisch laden und installieren.",
                            fontSize = 13.sp, color = Color.LightGray
                        )
                        OutlinedTextField(
                            value = otaVersionCode,
                            onValueChange = { otaVersionCode = it },
                            label = { Text("Versions-Code (z.B. 2)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = otaVersionName,
                            onValueChange = { otaVersionName = it },
                            label = { Text("Versions-Name (z.B. 1.1)") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(onClick = { filePickerLauncher.launch("application/vnd.android.package-archive") }) {
                                Text("APK Auswählen")
                            }
                            Text(selectedFileName.ifEmpty { "Keine Datei gewählt" }, color = Color.LightGray, maxLines = 1)
                        }

                        if (uploadStatus.isNotEmpty()) {
                            Text(uploadStatus, color = Color(0xFF6366F1), fontWeight = FontWeight.Bold)
                        }

                        Spacer(Modifier.height(16.dp))

                        Button(
                            onClick = {
                                val uri = selectedFileUri
                                val vCode = otaVersionCode.toIntOrNull()
                                if (uri == null || vCode == null || otaVersionName.isEmpty()) {
                                    Toast.makeText(context, "Bitte alle Felder korrekt ausfüllen!", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                scope.launch {
                                    uploadStatus = "Lese Datei..."
                                    val bytes = withContext(Dispatchers.IO) {
                                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                    }
                                    if (bytes == null) {
                                        uploadStatus = "Fehler beim Lesen der Datei"
                                        return@launch
                                    }
                                    uploadStatus = "Upload läuft..."
                                    val ok = withContext(Dispatchers.IO) {
                                        repository.uploadApk(serverUrl, vCode, otaVersionName, bytes, "update.apk")
                                    }
                                    uploadStatus = if (ok) "Update erfolgreich veröffentlicht!" else "Fehler beim Hochladen"
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Update veröffentlichen")
                        }

                        Button(
                            onClick = { currentScreen = "devices" },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Zurück")
                        }
                    }
                }
            }
        }

        // Modals / Dialogs
        if (showAddDeviceDialog) {
            var newDeviceName by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showAddDeviceDialog = false },
                title = { Text("Neues Gerät registrieren") },
                text = {
                    OutlinedTextField(
                        value = newDeviceName,
                        onValueChange = { newDeviceName = it },
                        label = { Text("Gerätename (z.B. Tom's Handy)") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newDeviceName.isNotBlank()) {
                                scope.launch {
                                    val dev = withContext(Dispatchers.IO) {
                                        repository.addDevice(serverUrl, newDeviceName)
                                    }
                                    showAddDeviceDialog = false
                                    if (dev != null) {
                                        refreshDevices()
                                        pairingDevice = dev
                                    } else {
                                        Toast.makeText(context, "Fehler beim Erstellen", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    ) {
                        Text("Registrieren")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddDeviceDialog = false }) { Text("Abbrechen") }
                }
            )
        }

        pairingDevice?.let { dev ->
            val clipboardManager = LocalClipboardManager.current
            val qrData = "{\"url\":\"$serverUrl\",\"id\":${dev.id},\"token\":\"${dev.deviceToken}\"}"
            val qrBitmap = remember(qrData) { generateQRCodeBitmap(qrData) }

            AlertDialog(
                onDismissRequest = { pairingDevice = null },
                title = { Text("Koppelung: ${dev.name}", fontWeight = FontWeight.Bold, color = Color.White) },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Scanne diesen QR-Code mit der RieseGuard-App auf dem Kinder-Handy, um es automatisch zu koppeln:",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )

                        if (qrBitmap != null) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                modifier = Modifier
                                    .size(200.dp)
                                    .padding(8.dp)
                                    .border(1.dp, Color.Gray, RoundedCornerShape(8.dp)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Image(
                                    bitmap = qrBitmap.asImageBitmap(),
                                    contentDescription = "Koppelungs QR-Code",
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        } else {
                            Text("QR-Code konnte nicht geladen werden.", color = Color.Red, fontSize = 11.sp)
                        }

                        HorizontalDivider(color = Color(0xFF2C2C3C))

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("Manuelle Zugangsdaten:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Color.White)
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Server URL: $serverUrl", fontSize = 11.sp, color = Color.LightGray, modifier = Modifier.weight(1f))
                                TextButton(onClick = { clipboardManager.setText(AnnotatedString(serverUrl)) }) {
                                    Text("Kopieren", fontSize = 10.sp, color = Color(0xFF6366F1))
                                }
                            }
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Geräte-ID: ${dev.id}", fontSize = 11.sp, color = Color.LightGray, modifier = Modifier.weight(1f))
                                TextButton(onClick = { clipboardManager.setText(AnnotatedString(dev.id.toString())) }) {
                                    Text("Kopieren", fontSize = 10.sp, color = Color(0xFF6366F1))
                                }
                            }
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Token: ${dev.deviceToken}", fontSize = 11.sp, color = Color.LightGray, modifier = Modifier.weight(1f), maxLines = 1)
                                TextButton(onClick = { clipboardManager.setText(AnnotatedString(dev.deviceToken)) }) {
                                    Text("Kopieren", fontSize = 10.sp, color = Color(0xFF6366F1))
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { pairingDevice = null },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1))
                    ) {
                        Text("Fertig", color = Color.White)
                    }
                }
            )
        }

        selectedDeviceForSchedule?.let { device ->
            var active by remember { mutableStateOf(device.scheduleActive) }
            var start by remember { mutableStateOf(device.scheduleStart ?: "21:00") }
            var end by remember { mutableStateOf(device.scheduleEnd ?: "07:00") }

            AlertDialog(
                onDismissRequest = { selectedDeviceForSchedule = null },
                title = { Text("🛌 Schlafenszeit einstellen") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Schlafenszeit sperren")
                            Switch(checked = active, onCheckedChange = { active = it })
                        }
                        OutlinedTextField(value = start, onValueChange = { start = it }, label = { Text("Sperren ab (HH:mm)") })
                        OutlinedTextField(value = end, onValueChange = { end = it }, label = { Text("Entsperren ab (HH:mm)") })
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                repository.setSchedule(serverUrl, device.id, active, start, end)
                            }
                            selectedDeviceForSchedule = null
                            if (ok) refreshDevices()
                            else Toast.makeText(context, "Speichern fehlgeschlagen", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Text("Speichern")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { selectedDeviceForSchedule = null }) { Text("Abbrechen") }
                }
            )
        }

        selectedDeviceForSchool?.let { device ->
            var active by remember { mutableStateOf(device.schoolActive) }
            var start by remember { mutableStateOf(device.schoolStart ?: "08:00") }
            var end by remember { mutableStateOf(device.schoolEnd ?: "13:00") }

            AlertDialog(
                onDismissRequest = { selectedDeviceForSchool = null },
                title = { Text("🏫 Schulmodus einstellen") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Schulmodus aktivieren")
                            Switch(checked = active, onCheckedChange = { active = it })
                        }
                        OutlinedTextField(value = start, onValueChange = { start = it }, label = { Text("Stumm ab (HH:mm)") })
                        OutlinedTextField(value = end, onValueChange = { end = it }, label = { Text("Laut ab (HH:mm)") })
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                repository.setSchool(serverUrl, device.id, active, start, end)
                            }
                            selectedDeviceForSchool = null
                            if (ok) refreshDevices()
                            else Toast.makeText(context, "Speichern fehlgeschlagen", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Text("Speichern")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { selectedDeviceForSchool = null }) { Text("Abbrechen") }
                }
            )
        }

        selectedDeviceForApps?.let { device ->
            AppsModal(
                device = device,
                serverUrl = serverUrl,
                repository = repository,
                onDismiss = { selectedDeviceForApps = null }
            )
        }

        if (showSecuritySettings) {
            var newPinVal by remember { mutableStateOf(appPin) }
            var newBiometricVal by remember { mutableStateOf(useBiometric) }
            
            AlertDialog(
                onDismissRequest = { showSecuritySettings = false },
                title = { Text("App-Schutz (PIN & Fingerabdruck)") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            text = "Schütze den Zugriff auf die Eltern-App vor unbefugtem Öffnen.",
                            fontSize = 13.sp,
                            color = Color.LightGray
                        )
                        
                        OutlinedTextField(
                            value = newPinVal,
                            onValueChange = { 
                                if (it.length <= 4 && it.all { c -> c.isDigit() }) {
                                    newPinVal = it
                                }
                            },
                            label = { Text("4-stellige PIN (leer zum Deaktivieren)") },
                            placeholder = { Text(if (appPin.isNotEmpty()) "PIN aktiv" else "Keine PIN") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Fingerabdruck nutzen", color = Color.White, fontSize = 14.sp)
                            Switch(
                                checked = newBiometricVal,
                                onCheckedChange = { newBiometricVal = it },
                                modifier = Modifier.scale(0.8f)
                            )
                        }

                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider(color = Color(0xFF2C2C3C))
                        Spacer(Modifier.height(8.dp))
                        
                        Button(
                            onClick = {
                                showSecuritySettings = false
                                showDeleteAccountConfirmation = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Eltern-Konto unwiderruflich löschen", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            appPin = newPinVal
                            useBiometric = newBiometricVal
                            sharedPrefs.edit()
                                .putString("app_pin", newPinVal)
                                .putBoolean("use_biometric", newBiometricVal)
                                .apply()
                            showSecuritySettings = false
                            Toast.makeText(context, "App-Schutz Einstellungen gespeichert", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1))
                    ) {
                        Text("Speichern", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSecuritySettings = false }) {
                        Text("Abbrechen", color = Color.White.copy(alpha = 0.6f))
                    }
                }
            )
        }

        deviceToDelete?.let { device ->
            AlertDialog(
                onDismissRequest = { deviceToDelete = null },
                title = { Text("Gerät löschen?") },
                text = {
                    Text("Möchtest du das Gerät '${device.name}' wirklich löschen? Alle zugehörigen App-Freigaben und Limits werden unwiderruflich entfernt.")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    repository.deleteDevice(serverUrl, device.id)
                                }
                                deviceToDelete = null
                                if (ok) refreshDevices()
                                else Toast.makeText(context, "Löschen fehlgeschlagen", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                    ) {
                        Text("Löschen", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deviceToDelete = null }) {
                        Text("Abbrechen", color = Color.White.copy(alpha = 0.6f))
                    }
                }
            )
        }

        if (showDeleteAccountConfirmation) {
            AlertDialog(
                onDismissRequest = { showDeleteAccountConfirmation = false },
                title = { Text("Eltern-Konto löschen?") },
                text = {
                    Text("Möchtest du dein Eltern-Konto wirklich löschen? Dieser Schritt löscht deinen Zugang permanent und kann nicht rückgängig gemacht werden.")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    repository.deleteAccount(serverUrl)
                                }
                                showDeleteAccountConfirmation = false
                                if (ok) {
                                    token = ""
                                    email = ""
                                    sharedPrefs.edit().remove("auth_token").remove("email").apply()
                                    isLoggedIn = false
                                    currentScreen = "login"
                                    Toast.makeText(context, "Konto erfolgreich gelöscht", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Konto-Löschen fehlgeschlagen", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                    ) {
                        Text("Konto löschen", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteAccountConfirmation = false }) {
                        Text("Abbrechen", color = Color.White.copy(alpha = 0.6f))
                    }
                }
            )
        }
    }
    }
}

@Composable
fun DeviceCard(
    device: DeviceInfo,
    onLockUnlock: (Boolean, String) -> Unit,
    onScheduleClick: () -> Unit,
    onSchoolClick: () -> Unit,
    onWebFilterToggle: (Boolean) -> Unit,
    onAppsClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onPairingClick: () -> Unit
) {
    val context = LocalContext.current
    var isOnline = false
    var timeText = "Noch nie"
    if (device.lastSeen != null) {
        try {
            val cleanTime = device.lastSeen.replace(" ", "T")
            val formatter = java.time.format.DateTimeFormatter.ISO_DATE_TIME
            val odt = java.time.OffsetDateTime.parse(cleanTime, formatter)
            val instant = odt.toInstant()
            isOnline = (System.currentTimeMillis() - instant.toEpochMilli()) < 20 * 60 * 1000
            val localDateTime = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
            timeText = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault()).format(localDateTime)
        } catch (e: Exception) {
            try {
                val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                val date = format.parse(device.lastSeen)
                if (date != null) {
                    isOnline = (System.currentTimeMillis() - date.time) < 20 * 60 * 1000
                    timeText = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(date)
                }
            } catch (_: Exception) {}
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF2C2C3C), RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(device.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = onDeleteClick,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Gerät löschen",
                            tint = Color.White.copy(alpha = 0.4f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(if (isOnline) Color(0xFF4CAF50) else Color.Red, RoundedCornerShape(5.dp))
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (isOnline) "Online" else "Offline", fontSize = 12.sp, color = Color.LightGray)
                }
            }

            Divider(color = Color(0xFF2C2C3C))

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Geräte ID: ${device.id}", fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
                Text("Token: ${device.deviceToken}", fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f), fontFamily = FontFamily.Monospace)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sperrstatus: ", fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
                    Text(
                        if (device.locked) "GESPERRT" else "OFFEN",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (device.locked) Color(0xFFEF4444) else Color(0xFF10B981)
                    )
                }
                if (device.locked && !device.lockReason.isNullOrEmpty()) {
                    Text("Grund: ${device.lockReason}", fontSize = 11.sp, color = Color(0xFFEF4444))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Schulmodus: ", fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
                    Text(
                        if (device.schoolActive) "AKTIV (${device.schoolStart}-${device.schoolEnd})" else "INAKTIV",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (device.schoolActive) Color(0xFF6366F1) else Color.White.copy(alpha = 0.4f)
                    )
                }
                Text("📱 Nutzungszeit heute: ${device.todayUsageMinutes ?: 0} Min.", fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
                Text("Letzter Sync: $timeText", fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📍 Standort: ", fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
                    if (device.latitude != null && device.longitude != null) {
                        Text(
                            "Karte öffnen",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF6366F1),
                            modifier = Modifier.clickable {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=${device.latitude},${device.longitude}"))
                                context.startActivity(intent)
                            }
                        )
                    } else {
                        Text("Kein Standort empfangen", fontSize = 11.sp, color = Color.White.copy(alpha = 0.4f))
                    }
                }
            }

            val appsList = device.apps ?: emptyList()
            if (appsList.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F0F15), RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "📊 Letzte Aktivitäten / App-Nutzung:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    val sortedApps = appsList.sortedByDescending { it.usageMinutes }.take(3)
                    sortedApps.forEach { app ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "• ${app.appName}",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.8f),
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${app.usageMinutes} Min.",
                                fontSize = 11.sp,
                                color = Color(0xFF10B981),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Web Filter Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F0F15), RoundedCornerShape(8.dp))
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🛡️ Jugendschutz Web-Filter", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Switch(
                    checked = device.webFilterActive,
                    onCheckedChange = { onWebFilterToggle(it) },
                    modifier = Modifier.scale(0.8f)
                )
            }

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Sperren / Entsperren Button
                Button(
                    onClick = {
                        if (device.locked) {
                            onLockUnlock(false, "")
                        } else {
                            onLockUnlock(true, "Das Gerät wurde gesperrt.")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (device.locked) Color(0xFF10B981) else Color(0xFFEF4444),
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1.3f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = "Lock",
                        modifier = Modifier.size(14.dp),
                        tint = Color.White
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (device.locked) "Entsperren" else "Sperren",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1
                    )
                }

                // Plan Button
                Button(
                    onClick = onScheduleClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2C2C3C),
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "⏰ Plan",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1
                    )
                }

                // Schule Button
                Button(
                    onClick = onSchoolClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2C2C3C),
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "🏫 Schule",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1
                    )
                }

                // Apps Button
                Button(
                    onClick = onAppsClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2C2C3C),
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "📱 Apps",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1
                    )
                }

                // Koppeln Button
                Button(
                    onClick = onPairingClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2C2C3C),
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1.2f)
                ) {
                    Text(
                        text = "📷 Koppeln",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
fun LoginScreen(
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    email: String,
    onEmailChange: (String) -> Unit,
    onLoginSuccess: (String, String) -> Unit,
    repository: ParentRepository
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var isRegisterTab by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F15))
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("RieseGuard", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color(0xFF6366F1))
        Text(
            "Schutz & Transparenz für deine Familie",
            fontSize = 14.sp,
            color = Color.LightGray,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { isRegisterTab = false },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (!isRegisterTab) Color(0xFF6366F1) else Color(0xFF1E1E2E)
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text("Anmelden")
            }
            Button(
                onClick = { isRegisterTab = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRegisterTab) Color(0xFF6366F1) else Color(0xFF1E1E2E)
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text("Registrieren")
            }
        }

        OutlinedTextField(
            value = serverUrl,
            onValueChange = onServerUrlChange,
            label = { Text("Server URL") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { onEmailChange(it.trim().lowercase()) },
            label = { Text("E-Mail Adresse") },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                autoCorrect = false
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Passwort") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(24.dp))

        if (isLoading) {
            CircularProgressIndicator()
        } else {
            Button(
                onClick = {
                    val cleanEmail = email.trim().lowercase()
                    if (serverUrl.isBlank() || cleanEmail.isBlank() || password.isBlank()) {
                        Toast.makeText(context, "Bitte alle Felder ausfüllen!", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    scope.launch {
                        isLoading = true
                        if (isRegisterTab) {
                            val ok = withContext(Dispatchers.IO) {
                                repository.register(serverUrl, cleanEmail, password)
                            }
                            isLoading = false
                            if (ok) {
                                Toast.makeText(context, "Registrierung erfolgreich! Bitte einloggen.", Toast.LENGTH_LONG).show()
                                isRegisterTab = false
                                password = ""
                            } else {
                                Toast.makeText(context, "Registrierung fehlgeschlagen.", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            val token = withContext(Dispatchers.IO) {
                                repository.login(serverUrl, cleanEmail, password)
                            }
                            isLoading = false
                            if (token != null) {
                                onLoginSuccess(token, cleanEmail)
                            } else {
                                Toast.makeText(context, "Login fehlgeschlagen. Überprüfe deine Daten.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isRegisterTab) "Konto erstellen" else "Anmelden")
            }
        }
    }
}

@Composable
fun AppsModal(
    device: DeviceInfo,
    serverUrl: String,
    repository: ParentRepository,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var appsList by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var limitsMap by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }

    val reloadData = {
        scope.launch {
            isLoading = true
            val apps = withContext(Dispatchers.IO) { repository.listApps(serverUrl, device.id) }
            val limits = withContext(Dispatchers.IO) { repository.getLimits(serverUrl, device.id) }
            appsList = apps
            limitsMap = limits.associate { it.packageName to it.dailyLimitMinutes }
            isLoading = false
        }
    }

    LaunchedEffect(device.id) {
        reloadData()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF0F0F15)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "App-Freigaben: ${device.name}",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Schließen",
                            tint = Color.White
                        )
                    }
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("App suchen...") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedLabelColor = Color.White.copy(alpha = 0.7f),
                        unfocusedLabelColor = Color.White.copy(alpha = 0.5f)
                    )
                )

                if (isLoading) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    val filtered = appsList.filter {
                        it.appName.contains(searchQuery, ignoreCase = true) ||
                                it.packageName.contains(searchQuery, ignoreCase = true)
                    }

                    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(filtered) { app ->
                            AppRow(
                                app = app,
                                initialLimit = limitsMap[app.packageName],
                                onToggle = { blocked ->
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) {
                                            repository.toggleAppBlock(serverUrl, device.id, app.packageName, blocked)
                                        }
                                        if (ok) {
                                            app.isBlocked = blocked
                                            Toast.makeText(context, if (blocked) "App gesperrt" else "App freigegeben", Toast.LENGTH_SHORT).show()
                                            
                                            // Trigger noticeable vibration
                                            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                vibrator?.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                                            } else {
                                                vibrator?.vibrate(100)
                                            }
                                        } else {
                                            Toast.makeText(context, "Fehler beim Umschalten", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onLimitSave = { minutes ->
                                    scope.launch {
                                        val ok = if (minutes == null) {
                                            withContext(Dispatchers.IO) { repository.deleteLimit(serverUrl, device.id, app.packageName) }
                                        } else {
                                            withContext(Dispatchers.IO) { repository.saveLimit(serverUrl, device.id, app.packageName, minutes) }
                                        }
                                        if (ok) {
                                            limitsMap = if (minutes == null) {
                                                limitsMap - app.packageName
                                             } else {
                                                limitsMap + (app.packageName to minutes)
                                             }
                                            Toast.makeText(context, if (minutes == null) "Limit gelöscht" else "Limit gespeichert", Toast.LENGTH_SHORT).show()
                                            
                                            // Trigger noticeable vibration
                                            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                vibrator?.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                                            } else {
                                                vibrator?.vibrate(100)
                                            }
                                        } else {
                                            Toast.makeText(context, "Fehler beim Speichern des Limits", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            )
                        }
                    }
                }

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Fertig", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun AppRow(
    app: AppInfo,
    initialLimit: Int?,
    onToggle: (Boolean) -> Unit,
    onLimitSave: (Int?) -> Unit
) {
    var isBlocked by remember { mutableStateOf(app.isBlocked) }
    var limitVal by remember { mutableStateOf(initialLimit?.toString() ?: "") }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF2C2C3C), RoundedCornerShape(8.dp))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(app.appName, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                    Text(app.packageName, fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = Color.Gray, maxLines = 1)
                    
                    val usage = app.usageMinutes
                    val limit = initialLimit
                    val usageText = if (limit != null) {
                        val remaining = limit - usage
                        if (remaining <= 0) {
                            "$usage / $limit Min. (Limit erreicht! ⚠️)"
                        } else {
                            "$usage / $limit Min. ($remaining Min. übrig)"
                        }
                    } else {
                        "$usage Min. genutzt"
                    }
                    val usageColor = if (limit != null && usage >= limit) Color(0xFFEF4444) else Color(0xFF10B981)
                    
                    Text(
                        text = usageText,
                        fontSize = 11.sp,
                        color = usageColor,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Switch(
                    checked = isBlocked,
                    onCheckedChange = {
                        isBlocked = it
                        onToggle(it)
                    },
                    modifier = Modifier.scale(0.8f)
                )
            }

            if (!isBlocked) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("⏳ Limit:", fontSize = 12.sp, color = Color.LightGray)
                        
                        // Minus Button
                        IconButton(
                            onClick = {
                                val current = limitVal.toIntOrNull() ?: 0
                                if (current > 15) {
                                    limitVal = (current - 15).toString()
                                } else {
                                    limitVal = ""
                                }
                            },
                            modifier = Modifier
                                .size(28.dp)
                                .background(Color(0xFF2C2C3C), CircleShape)
                        ) {
                            Text("-", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center)
                        }

                        OutlinedTextField(
                            value = limitVal,
                            onValueChange = { 
                                if (it.all { c -> c.isDigit() }) {
                                    limitVal = it
                                }
                            },
                            placeholder = { Text("keins", fontSize = 11.sp, color = Color.Gray) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, textAlign = TextAlign.Center),
                            singleLine = true,
                            modifier = Modifier
                                .width(70.dp)
                                .height(46.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF6366F1),
                                unfocusedBorderColor = Color(0xFF2C2C3C)
                            )
                        )

                        // Plus Button
                        IconButton(
                            onClick = {
                                val current = limitVal.toIntOrNull() ?: 0
                                limitVal = (current + 15).toString()
                            },
                            modifier = Modifier
                                .size(28.dp)
                                .background(Color(0xFF2C2C3C), CircleShape)
                        ) {
                            Text("+", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center)
                        }

                        Text("Min.", fontSize = 12.sp, color = Color.LightGray)
                    }

                    Button(
                        onClick = {
                            val mins = limitVal.toIntOrNull()
                            onLimitSave(mins)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                        modifier = Modifier.height(34.dp),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                    ) {
                        Text("Speichern", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// Extension to scale components easily
fun Modifier.scale(scale: Float): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout((placeable.width * scale).toInt(), (placeable.height * scale).toInt()) {
            placeable.placeRelativeWithLayer(0, 0, layerBlock = {
                scaleX = scale
                scaleY = scale
            })
        }
    }
)

@Composable
fun PinLockScreen(
    correctPin: String,
    useBiometric: Boolean,
    onUnlock: () -> Unit,
    onBiometricTrigger: () -> Unit
) {
    var enteredPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    val context = LocalContext.current

    // Automatically trigger biometrics on start if enabled
    LaunchedEffect(Unit) {
        if (useBiometric) {
            onBiometricTrigger()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F15)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = "Sperre",
                tint = Color(0xFF6366F1),
                modifier = Modifier.size(64.dp)
            )

            Text(
                text = "RieseGuard gesperrt",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Text(
                text = if (errorMessage.isEmpty()) "PIN eingeben" else errorMessage,
                fontSize = 14.sp,
                color = if (errorMessage.isEmpty()) Color.LightGray else Color(0xFFEF4444)
            )

            // PIN Dots representation
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(4) { index ->
                    val isFilled = index < enteredPin.length
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .border(2.dp, Color(0xFF6366F1), CircleShape)
                            .background(if (isFilled) Color(0xFF6366F1) else Color.Transparent, CircleShape)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Custom Keypad
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val rowsStandard = listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf("FP", "0", "DEL")
                )

                rowsStandard.forEach { row ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        row.forEach { char ->
                            if (char == "FP") {
                                if (useBiometric) {
                                    IconButton(
                                        onClick = onBiometricTrigger,
                                        modifier = Modifier
                                            .size(64.dp)
                                            .background(Color(0xFF1E1E2A), CircleShape)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Lock,
                                            contentDescription = "Biometrie",
                                            tint = Color(0xFF6366F1),
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                } else {
                                    Spacer(modifier = Modifier.size(64.dp))
                                }
                            } else if (char == "DEL") {
                                IconButton(
                                    onClick = {
                                        if (enteredPin.isNotEmpty()) {
                                            enteredPin = enteredPin.dropLast(1)
                                        }
                                    },
                                    modifier = Modifier
                                        .size(64.dp)
                                        .background(Color(0xFF1E1E2A), CircleShape)
                                ) {
                                    Text("⌫", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                Button(
                                    onClick = {
                                        if (enteredPin.length < 4) {
                                            enteredPin += char
                                            errorMessage = ""
                                            if (enteredPin.length == 4) {
                                                if (enteredPin == correctPin) {
                                                    onUnlock()
                                                } else {
                                                    errorMessage = "Falsche PIN"
                                                    enteredPin = ""
                                                }
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E2A)),
                                    shape = CircleShape,
                                    modifier = Modifier.size(64.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text(char, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

fun generateQRCodeBitmap(content: String, size: Int = 512): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}

fun vibrate(context: Context, durationMs: Long = 30) {
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
    } else {
        vibrator?.vibrate(durationMs)
    }
}

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: androidx.compose.ui.graphics.Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit
) {
    val context = LocalContext.current
    androidx.compose.material3.Button(
        onClick = {
            vibrate(context, 35)
            onClick()
        },
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content
    )
}

@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    androidx.compose.material3.IconButton(
        onClick = {
            vibrate(context, 35)
            onClick()
        },
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        interactionSource = interactionSource,
        content = content
    )
}

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: androidx.compose.ui.graphics.Shape = ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit
) {
    val context = LocalContext.current
    androidx.compose.material3.TextButton(
        onClick = {
            vibrate(context, 35)
            onClick()
        },
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content
    )
}
