package mu.nada.unlocker

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mu.nada.unlocker.data.*
import mu.nada.unlocker.log.AppLogger
import mu.nada.unlocker.log.LogLevel
import mu.nada.unlocker.security.BiometricHelper
import mu.nada.unlocker.security.HostKeyManager
import mu.nada.unlocker.security.TofuDecision
import mu.nada.unlocker.service.UnlockForegroundService
import mu.nada.unlocker.ssh.SshUnlocker
import mu.nada.unlocker.ssh.UnlockResult

class MainActivity : FragmentActivity() {

    private lateinit var keyManager: KeyManager
    private lateinit var hostKeyManager: HostKeyManager
    private val scanner = NetworkScanner()
    private val unlocker = SshUnlocker()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KeyManager.initBouncyCastle()
        keyManager = KeyManager(this)
        hostKeyManager = HostKeyManager(this)

        // Protect secret credentials from screen recorders and multitasking snapshots
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        AppLogger.i("MainActivity", "Unlocker initialized. Ready.")

        if (hostKeyManager.isAnyTriggerEnabled()) {
            UnlockForegroundService.start(this)
        }

        handleIncomingIntent(intent)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF64B5F6),
                    secondary = Color(0xFF81C784),
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E),
                    error = Color(0xFFE57373)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var isAppUnlocked by remember { mutableStateOf(!hostKeyManager.isAppLockEnabled()) }

                    LaunchedEffect(Unit) {
                        if (hostKeyManager.isAppLockEnabled() && !isAppUnlocked) {
                            BiometricHelper.authenticate(
                                activity = this@MainActivity,
                                title = "Unlocker Locked",
                                subtitle = "Authenticate with fingerprint to open app",
                                onSuccess = { isAppUnlocked = true },
                                onError = { msg ->
                                    AppLogger.w("MainActivity", "App lock auth failed: $msg")
                                }
                            )
                        }
                    }

                    if (!isAppUnlocked) {
                        AppLockedScreen(
                            onAuthenticate = {
                                BiometricHelper.authenticate(
                                    activity = this@MainActivity,
                                    title = "Unlocker Locked",
                                    subtitle = "Authenticate with fingerprint to open app",
                                    onSuccess = { isAppUnlocked = true },
                                    onError = { msg ->
                                        Toast.makeText(this@MainActivity, "Authentication failed: $msg", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        )
                    } else {
                        MainScreen(
                            activity = this,
                            keyManager = keyManager,
                            hostKeyManager = hostKeyManager,
                            scanner = scanner,
                            unlocker = unlocker,
                            onCopy = { label, text ->
                                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
                                Toast.makeText(this, "$label copied to clipboard!", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == UnlockForegroundService.ACTION_CONFIRM_BIOMETRIC_UNLOCK) {
            val ip = intent.getStringExtra(UnlockForegroundService.EXTRA_TARGET_IP)
            val port = intent.getIntExtra(UnlockForegroundService.EXTRA_TARGET_PORT, 22)
            val chName = intent.getStringExtra(UnlockForegroundService.EXTRA_TARGET_CHANNEL)
            val channel = chName?.let { runCatching { NetworkChannel.valueOf(it) }.getOrNull() } ?: NetworkChannel.LAN
            val label = intent.getStringExtra(UnlockForegroundService.EXTRA_TARGET_LABEL) ?: "Laptop"

            if (ip != null) {
                BiometricHelper.authenticate(
                    activity = this,
                    title = "Confirm Unlock: $label",
                    subtitle = "Verify fingerprint to unlock $ip (${channel.name})",
                    onSuccess = {
                        val serviceIntent = Intent(this, UnlockForegroundService::class.java).apply {
                            action = UnlockForegroundService.ACTION_TRIGGER_UNLOCK
                            putExtra(UnlockForegroundService.EXTRA_TARGET_IP, ip)
                            putExtra(UnlockForegroundService.EXTRA_TARGET_PORT, port)
                            putExtra(UnlockForegroundService.EXTRA_TARGET_CHANNEL, channel.name)
                            putExtra(UnlockForegroundService.EXTRA_TARGET_LABEL, label)
                        }
                        startService(serviceIntent)
                    },
                    onError = { err ->
                        Toast.makeText(this, "Biometric auth cancelled: $err", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}

@Composable
fun AppLockedScreen(onAuthenticate: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(id = R.drawable.app_logo),
            contentDescription = "App Logo",
            modifier = Modifier.size(72.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text("App is Locked", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Biometric authentication is required to access Unlocker.",
            fontSize = 13.sp,
            color = Color.Gray,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onAuthenticate,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Icon(Icons.Default.Fingerprint, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Unlock with Biometrics", fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    activity: FragmentActivity,
    keyManager: KeyManager,
    hostKeyManager: HostKeyManager,
    scanner: NetworkScanner,
    unlocker: SshUnlocker,
    onCopy: (String, String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableStateOf(0) } // 0: Dashboard, 1: Settings, 2: Logs

    var isScanning by remember { mutableStateOf(false) }
    var isUnlocking by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var discoveredDevices by remember { mutableStateOf<List<DiscoveredDevice>>(emptyList()) }

    var pubKey by remember { mutableStateOf(keyManager.getPublicKeyOpenSsh()) }
    var hasPassword by remember { mutableStateOf(keyManager.hasSavedPassword()) }
    var showPasswordDialog by remember { mutableStateOf(false) }

    // Permission launcher for Android 13+ POST_NOTIFICATIONS
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (isGranted) {
                AppLogger.i("MainScreen", "Notification permission granted.")
            } else {
                AppLogger.w("MainScreen", "Notification permission denied; confirmation alerts may not be visible.")
            }
        }
    )

    fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(Unit) {
        if (hostKeyManager.isAnyTriggerEnabled()) {
            checkNotificationPermission()
        }
    }

    // Single host unlock executor
    fun executeHostUnlock(target: DiscoveredDevice) {
        val password = keyManager.getSavedPassword()
        if (password.isNullOrEmpty()) {
            Toast.makeText(context, "Please set LUKS disk password in Settings first", Toast.LENGTH_SHORT).show()
            showPasswordDialog = true
            return
        }

        val runUnlock = {
            scope.launch {
                isUnlocking = true
                statusText = "Unlocking ${target.label} on ${target.ip}..."
                UnlockForegroundService.cancelActiveBackgroundJob("Manual host unlock")
                UnlockForegroundService.isUserJobRunning = true

                try {
                    val privKeyPem = keyManager.getPrivateKeyPem()
                    val result = unlocker.unlock(
                        host = target.ip,
                        port = target.port,
                        password = password,
                        privateKeyPem = privKeyPem,
                        mapperTarget = hostKeyManager.getMapperTarget(),
                        pollTimeoutSeconds = hostKeyManager.getPollTimeoutSeconds(),
                        hostKeyManager = hostKeyManager,
                        tofuPrompt = { _, _, _, _, _ -> TofuDecision.REJECT }
                    )

                    withContext(Dispatchers.Main) {
                        when (result) {
                            is UnlockResult.Success -> {
                                hostKeyManager.setLastIp(target.channel, target.ip)
                                Toast.makeText(context, "🎉 ${target.label} Unlocked Successfully!", Toast.LENGTH_LONG).show()
                                statusText = "${target.label} unlocked!"
                            }
                            is UnlockResult.Failure -> {
                                Toast.makeText(context, "Unlock failed: ${result.error}", Toast.LENGTH_SHORT).show()
                                statusText = "Unlock failed: ${result.error}"
                            }
                        }
                    }
                } finally {
                    isUnlocking = false
                    UnlockForegroundService.isUserJobRunning = false
                }
            }
        }

        if (hostKeyManager.isBiometricUnlockRequired()) {
            BiometricHelper.authenticate(
                activity = activity,
                title = "Authorize LUKS Unlock",
                subtitle = "Confirm fingerprint to unlock ${target.label}",
                onSuccess = { runUnlock() },
                onError = { err ->
                    Toast.makeText(context, "Biometric auth cancelled: $err", Toast.LENGTH_SHORT).show()
                }
            )
        } else {
            runUnlock()
        }
    }

    // Scan & Unlock All executor
    fun executeScanAndUnlockAll() {
        val password = keyManager.getSavedPassword()
        if (password.isNullOrEmpty()) {
            Toast.makeText(context, "Please set LUKS disk password in Settings first", Toast.LENGTH_SHORT).show()
            showPasswordDialog = true
            return
        }

        val runScanAndUnlock = {
            scope.launch {
                // Cancel any running background job immediately
                UnlockForegroundService.cancelActiveBackgroundJob("User initiated SCAN & UNLOCK ALL")
                UnlockForegroundService.isUserJobRunning = true

                isUnlocking = true
                statusText = "Scanning active networks for trusted laptops..."

                try {
                    val found = scanner.scanSubnetForLuks(hostKeyManager = hostKeyManager)
                    discoveredDevices = found

                    if (found.isEmpty()) {
                        statusText = "No trusted laptops found on network."
                        Toast.makeText(context, "No trusted laptops detected", Toast.LENGTH_SHORT).show()
                        return@launch
                    }

                    statusText = "Found ${found.size} trusted laptop(s). Unlocking..."
                    val privKeyPem = keyManager.getPrivateKeyPem()
                    for (target in found) {
                        statusText = "Unlocking ${target.label} on ${target.ip}..."
                        val result = unlocker.unlock(
                            host = target.ip,
                            port = target.port,
                            password = password,
                            privateKeyPem = privKeyPem,
                            mapperTarget = hostKeyManager.getMapperTarget(),
                            pollTimeoutSeconds = hostKeyManager.getPollTimeoutSeconds(),
                            hostKeyManager = hostKeyManager,
                            tofuPrompt = { _, _, _, _, _ -> TofuDecision.REJECT }
                        )

                        if (result is UnlockResult.Success) {
                            hostKeyManager.setLastIp(target.channel, target.ip)
                        }
                    }

                    Toast.makeText(context, "🎉 Unlock All completed!", Toast.LENGTH_LONG).show()
                    statusText = "Unlock All finished."
                } finally {
                    isUnlocking = false
                    UnlockForegroundService.isUserJobRunning = false
                }
            }
        }

        if (hostKeyManager.isBiometricUnlockRequired()) {
            BiometricHelper.authenticate(
                activity = activity,
                title = "Authorize Unlock All",
                subtitle = "Confirm fingerprint to unlock all discovered laptops",
                onSuccess = { runScanAndUnlock() },
                onError = { err ->
                    Toast.makeText(context, "Biometric auth cancelled: $err", Toast.LENGTH_SHORT).show()
                }
            )
        } else {
            runScanAndUnlock()
        }
    }

    // Manual Scan executor (Discover only, no unlock)
    fun executeScanOnly() {
        scope.launch {
            // Cancel any running background job immediately
            UnlockForegroundService.cancelActiveBackgroundJob("User initiated SCAN")
            UnlockForegroundService.isUserJobRunning = true

            isScanning = true
            statusText = "Scanning active networks for trusted laptops..."

            try {
                val found = scanner.scanSubnetForLuks(hostKeyManager = hostKeyManager)
                discoveredDevices = found
                if (found.isEmpty()) {
                    statusText = "Scan completed: 0 trusted laptops found."
                    Toast.makeText(context, "No trusted laptops detected", Toast.LENGTH_SHORT).show()
                } else {
                    statusText = "Scan completed: Found ${found.size} trusted laptop(s)."
                    Toast.makeText(context, "Found ${found.size} trusted laptop(s)", Toast.LENGTH_SHORT).show()
                }
            } finally {
                isScanning = false
                UnlockForegroundService.isUserJobRunning = false
            }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Devices, contentDescription = "Dashboard") },
                    label = { Text("Dashboard") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Code, contentDescription = "Logs") },
                    label = { Text("Logs") }
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (selectedTab) {
                0 -> DashboardTab(
                    hostKeyManager = hostKeyManager,
                    hasPassword = hasPassword,
                    isScanning = isScanning,
                    isUnlocking = isUnlocking,
                    statusText = statusText,
                    discoveredDevices = discoveredDevices,
                    onScanOnly = { executeScanOnly() },
                    onScanAndUnlockAll = { executeScanAndUnlockAll() },
                    onUnlockHost = { executeHostUnlock(it) },
                    onNavigateToSettings = { selectedTab = 1 }
                )
                1 -> SettingsTab(
                    activity = activity,
                    keyManager = keyManager,
                    hostKeyManager = hostKeyManager,
                    hasPassword = hasPassword,
                    pubKey = pubKey,
                    onPasswordUpdated = { hasPassword = keyManager.hasSavedPassword() },
                    onPubKeyUpdated = { pubKey = it },
                    onCopy = onCopy,
                    onPermissionsCheck = { checkNotificationPermission() }
                )
                2 -> LogsTab(onCopy = onCopy)
            }

            // Secure Set / Change Password Dialog
            if (showPasswordDialog) {
                PasswordEditDialog(
                    onDismiss = { showPasswordDialog = false },
                    onSave = { newPass ->
                        keyManager.savePassword(newPass)
                        hasPassword = true
                        showPasswordDialog = false
                        Toast.makeText(context, "LUKS disk password saved securely!", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}

@Composable
fun DashboardTab(
    hostKeyManager: HostKeyManager,
    hasPassword: Boolean,
    isScanning: Boolean,
    isUnlocking: Boolean,
    statusText: String?,
    discoveredDevices: List<DiscoveredDevice>,
    onScanOnly: () -> Unit,
    onScanAndUnlockAll: () -> Unit,
    onUnlockHost: (DiscoveredDevice) -> Unit,
    onNavigateToSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(id = R.drawable.app_logo),
                    contentDescription = "App Logo",
                    modifier = Modifier.size(36.dp)
                )
                Column {
                    Text(
                        text = "UNLOCKER",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    val activeTriggers = mutableListOf<String>()
                    if (hostKeyManager.isTriggerUsbEnabled()) activeTriggers.add("USB")
                    if (hostKeyManager.isTriggerHotspotEnabled()) activeTriggers.add("Hotspot")
                    if (hostKeyManager.isTriggerWifiEnabled()) activeTriggers.add("Wi-Fi")
                    if (hostKeyManager.isTriggerScreenUnlockEnabled()) activeTriggers.add("Screen")

                    Text(
                        text = if (activeTriggers.isNotEmpty()) "Monitoring: ${activeTriggers.joinToString(", ")}" else "Monitoring: Off",
                        fontSize = 11.sp,
                        color = if (activeTriggers.isNotEmpty()) Color(0xFF81C784) else Color.Gray
                    )
                }
            }

            IconButton(onClick = onNavigateToSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.Gray)
            }
        }

        // Action Buttons Section
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onScanAndUnlockAll,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(14.dp),
                enabled = !isScanning && !isUnlocking
            ) {
                if (isUnlocking) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Unlocking...", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                } else {
                    Icon(Icons.Default.LockOpen, contentDescription = null)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("SCAN & UNLOCK ALL", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }

            OutlinedButton(
                onClick = onScanOnly,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = !isScanning && !isUnlocking
            ) {
                if (isScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Scanning Network...", fontWeight = FontWeight.SemiBold)
                } else {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("SCAN (DISCOVER ONLY)", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Status banner if present
        if (!statusText.isNullOrBlank()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2836)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Text(statusText, fontSize = 12.sp, color = Color.LightGray)
                }
            }
        }

        // Section: Discovered Laptops
        Text(
            text = "Discovered Laptops (${discoveredDevices.size}):",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )

        if (discoveredDevices.isEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.Laptop, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.DarkGray)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "No Trusted Laptops Found",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.LightGray
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    val guidanceText = when {
                        !hostKeyManager.hasAnyTrustedKeys() ->
                            "No trusted laptop keys added yet. Add your laptop key fingerprint in Settings to discover it."
                        !hasPassword ->
                            "No disk password saved yet. Set your LUKS password in Settings to enable unlocking."
                        else ->
                            "Ensure your laptop is in early-boot (initramfs) with Dropbear active, connected via USB, Hotspot, or Wi-Fi, then tap 'Scan'."
                    }
                    Text(
                        text = guidanceText,
                        fontSize = 12.sp,
                        color = Color.Gray,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    if (!hostKeyManager.hasAnyTrustedKeys() || !hasPassword) {
                        Spacer(modifier = Modifier.height(16.dp))
                        TextButton(onClick = onNavigateToSettings) {
                            Text("Open Settings")
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(discoveredDevices) { dev ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(dev.label, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    Badge(
                                        containerColor = when (dev.channel) {
                                            NetworkChannel.USB -> Color(0xFF00ACC1)
                                            NetworkChannel.HOTSPOT -> Color(0xFF8E24AA)
                                            NetworkChannel.LAN -> Color(0xFF1E88E5)
                                        }
                                    ) {
                                        Text(dev.channel.name, fontSize = 9.sp, color = Color.White)
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("${dev.ip}:${dev.port}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color.Gray)
                                if (dev.fingerprint != null) {
                                    Text(
                                        dev.fingerprint.take(24) + "...",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            Button(
                                onClick = { onUnlockHost(dev) },
                                shape = RoundedCornerShape(8.dp),
                                enabled = !isUnlocking
                            ) {
                                Text("Unlock", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsTab(
    activity: FragmentActivity,
    keyManager: KeyManager,
    hostKeyManager: HostKeyManager,
    hasPassword: Boolean,
    pubKey: String,
    onPasswordUpdated: () -> Unit,
    onPubKeyUpdated: (String) -> Unit,
    onCopy: (String, String) -> Unit,
    onPermissionsCheck: () -> Unit
) {
    val context = LocalContext.current
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showAddKeyDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showRegenerateDialog by remember { mutableStateOf(false) }

    var trustedKeysList by remember { mutableStateOf(hostKeyManager.getTrustedKeys()) }
    var channelPriority by remember { mutableStateOf(hostKeyManager.getChannelPriority()) }

    var triggerUsb by remember { mutableStateOf(hostKeyManager.isTriggerUsbEnabled()) }
    var triggerHotspot by remember { mutableStateOf(hostKeyManager.isTriggerHotspotEnabled()) }
    var triggerWifi by remember { mutableStateOf(hostKeyManager.isTriggerWifiEnabled()) }
    var triggerScreen by remember { mutableStateOf(hostKeyManager.isTriggerScreenUnlockEnabled()) }

    var requireBiometrics by remember { mutableStateOf(hostKeyManager.isBiometricUnlockRequired()) }
    var appLockEnabled by remember { mutableStateOf(hostKeyManager.isAppLockEnabled()) }

    var mapperTarget by remember { mutableStateOf(hostKeyManager.getMapperTarget()) }
    var pollTimeoutSec by remember { mutableStateOf(hostKeyManager.getPollTimeoutSeconds()) }
    var targetPortsInput by remember { mutableStateOf(hostKeyManager.getTargetPorts().joinToString(", ")) }
    var bannerRegexInput by remember { mutableStateOf(hostKeyManager.getBannerRegex()) }

    fun refreshServiceState() {
        if (hostKeyManager.isAnyTriggerEnabled()) {
            onPermissionsCheck()
            UnlockForegroundService.start(context)
        } else {
            UnlockForegroundService.stop(context)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("Settings & Security", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        // Section 1: LUKS Passphrase (Non-viewable, secure change only)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("LUKS Disk Password", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(
                                if (hasPassword) "Password is set and encrypted in KeyStore (••••••••)" else "No password set",
                                fontSize = 11.sp,
                                color = if (hasPassword) Color(0xFF81C784) else MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { showPasswordDialog = true }) {
                            Text(if (hasPassword) "Change Password" else "Set Password", fontSize = 12.sp)
                        }
                        if (hasPassword) {
                            OutlinedButton(onClick = {
                                keyManager.clearPassword()
                                onPasswordUpdated()
                                Toast.makeText(context, "Password removed", Toast.LENGTH_SHORT).show()
                            }) {
                                Text("Remove", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // Section 2: Trusted Laptops
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Trusted Laptops (${trustedKeysList.size}):", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Button(onClick = { showAddKeyDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add Key", fontSize = 12.sp)
                        }
                    }

                    if (trustedKeysList.isEmpty()) {
                        Text(
                            "No trusted laptops added yet. Only authorized laptops with pinned fingerprints will be discovered and unlocked.",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    } else {
                        trustedKeysList.forEach { keyItem ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF2A2A2A), RoundedCornerShape(8.dp))
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(keyItem.label, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(
                                        keyItem.fingerprint,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        hostKeyManager.untrustFingerprint(keyItem.fingerprint)
                                        trustedKeysList = hostKeyManager.getTrustedKeys()
                                    }
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section 3: Automatic Background Triggers
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Automatic Triggers & Confirmation", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        "When an event occurs, Unlocker finds your laptop and prompts you for confirmation. Unlocker NEVER unlocks silently without confirmation.",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )

                    HorizontalDivider(color = Color.DarkGray)

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("USB Connection", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text("Scan USB when cable connected", fontSize = 10.sp, color = Color.Gray)
                        }
                        Switch(checked = triggerUsb, onCheckedChange = {
                            triggerUsb = it
                            hostKeyManager.setTriggerUsbEnabled(it)
                            refreshServiceState()
                        })
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Wi-Fi Hotspot", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text("Scan Hotspot when phone AP enabled", fontSize = 10.sp, color = Color.Gray)
                        }
                        Switch(checked = triggerHotspot, onCheckedChange = {
                            triggerHotspot = it
                            hostKeyManager.setTriggerHotspotEnabled(it)
                            refreshServiceState()
                        })
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Wi-Fi / Local Network", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text("Scan local LAN when network available", fontSize = 10.sp, color = Color.Gray)
                        }
                        Switch(checked = triggerWifi, onCheckedChange = {
                            triggerWifi = it
                            hostKeyManager.setTriggerWifiEnabled(it)
                            refreshServiceState()
                        })
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Screen Unlock", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text("Scan all channels when phone unlocked", fontSize = 10.sp, color = Color.Gray)
                        }
                        Switch(checked = triggerScreen, onCheckedChange = {
                            triggerScreen = it
                            hostKeyManager.setTriggerScreenUnlockEnabled(it)
                            refreshServiceState()
                        })
                    }
                }
            }
        }

        // Section 4: Security & Biometrics
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Biometric Security", fontWeight = FontWeight.Bold, fontSize = 14.sp)

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Require Fingerprint for All Unlocks", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text("Prompts for biometric confirmation before sending password", fontSize = 10.sp, color = Color.Gray)
                        }
                        Switch(checked = requireBiometrics, onCheckedChange = {
                            requireBiometrics = it
                            hostKeyManager.setBiometricUnlockRequired(it)
                        })
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("App Lock on Launch", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text("Requires fingerprint authentication to open Unlocker", fontSize = 10.sp, color = Color.Gray)
                        }
                        Switch(checked = appLockEnabled, onCheckedChange = {
                            appLockEnabled = it
                            hostKeyManager.setAppLockEnabled(it)
                        })
                    }
                }
            }
        }

        // Section 5: Network Channels & Priority
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Network Channels & Priority", fontWeight = FontWeight.Bold, fontSize = 14.sp)

                    channelPriority.forEachIndexed { index, channel ->
                        val isEnabled = hostKeyManager.isChannelEnabled(channel)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF2A2A2A), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = isEnabled,
                                    onCheckedChange = { checked ->
                                        hostKeyManager.setChannelEnabled(channel, checked)
                                        channelPriority = hostKeyManager.getChannelPriority()
                                    }
                                )
                                Text("${index + 1}. ${channel.name}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Row {
                                IconButton(
                                    onClick = {
                                        if (index > 0) {
                                            val reordered = channelPriority.toMutableList()
                                            val item = reordered.removeAt(index)
                                            reordered.add(index - 1, item)
                                            channelPriority = reordered
                                            hostKeyManager.setChannelPriority(reordered)
                                        }
                                    },
                                    enabled = index > 0,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.ArrowUpward, contentDescription = "Up", modifier = Modifier.size(16.dp))
                                }
                                IconButton(
                                    onClick = {
                                        if (index < channelPriority.size - 1) {
                                            val reordered = channelPriority.toMutableList()
                                            val item = reordered.removeAt(index)
                                            reordered.add(index + 1, item)
                                            channelPriority = reordered
                                            hostKeyManager.setChannelPriority(reordered)
                                        }
                                    },
                                    enabled = index < channelPriority.size - 1,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.ArrowDownward, contentDescription = "Down", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section 6: Advanced & SSH Identity
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Advanced Options & SSH Identity", fontWeight = FontWeight.Bold, fontSize = 14.sp)

                    Text("Client SSH Public Key (add to laptop's authorized_keys):", fontSize = 11.sp, color = Color.Gray)
                    SelectionContainer {
                        Text(pubKey, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary, maxLines = 2)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onCopy("Unlocker SSH Key", pubKey) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy Key", fontSize = 11.sp)
                        }
                        OutlinedButton(onClick = { showRegenerateDialog = true }) {
                            Text("Regenerate", fontSize = 11.sp)
                        }
                        OutlinedButton(onClick = { showImportDialog = true }) {
                            Text("Import", fontSize = 11.sp)
                        }
                    }

                    HorizontalDivider(color = Color.DarkGray)

                    OutlinedTextField(
                        value = mapperTarget,
                        onValueChange = {
                            mapperTarget = it
                            hostKeyManager.setMapperTarget(it)
                        },
                        label = { Text("LUKS Mapper Target (e.g. auto)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = pollTimeoutSec.toString(),
                        onValueChange = {
                            val sec = it.toIntOrNull() ?: 15
                            pollTimeoutSec = sec
                            hostKeyManager.setPollTimeoutSeconds(sec)
                        },
                        label = { Text("Mapper Poll Timeout (seconds)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = targetPortsInput,
                        onValueChange = {
                            targetPortsInput = it
                            val ports = it.split(",").mapNotNull { p -> p.trim().toIntOrNull() }
                            if (ports.isNotEmpty()) hostKeyManager.setTargetPorts(ports)
                        },
                        label = { Text("Target SSH Ports (e.g. 22)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = bannerRegexInput,
                        onValueChange = {
                            bannerRegexInput = it
                            hostKeyManager.setBannerRegex(it)
                        },
                        label = { Text("SSH Banner Filter Regex") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }
        }
    }

    // Password Edit Dialog
    if (showPasswordDialog) {
        PasswordEditDialog(
            onDismiss = { showPasswordDialog = false },
            onSave = { newPass ->
                keyManager.savePassword(newPass)
                onPasswordUpdated()
                showPasswordDialog = false
                Toast.makeText(context, "LUKS disk password saved securely!", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // Add Key Dialog
    if (showAddKeyDialog) {
        var labelInput by remember { mutableStateOf("Laptop") }
        var fpInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddKeyDialog = false },
            title = { Text("Add Trusted Laptop Key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Enter the SSH host key fingerprint of your laptop's Dropbear server (displayed by laptop installer):",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    OutlinedTextField(
                        value = labelInput,
                        onValueChange = { labelInput = it },
                        label = { Text("Device Name (e.g. Work ThinkPad)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = fpInput,
                        onValueChange = { fpInput = it },
                        label = { Text("Fingerprint (SHA256:...)") },
                        placeholder = { Text("SHA256:JajCs+5L6c1Ey...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (fpInput.isNotBlank()) {
                        hostKeyManager.trustFingerprint(fpInput.trim(), labelInput.trim())
                        trustedKeysList = hostKeyManager.getTrustedKeys()
                        showAddKeyDialog = false
                        Toast.makeText(context, "Laptop key added to trusted list!", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddKeyDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Import Key Dialog
    if (showImportDialog) {
        var importKeyInput by remember { mutableStateOf("") }
        var importError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import SSH Private Key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Paste your OpenSSH or PEM private key:", fontSize = 12.sp)
                    OutlinedTextField(
                        value = importKeyInput,
                        onValueChange = { importKeyInput = it; importError = null },
                        label = { Text("Private Key") },
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    )
                    if (importError != null) {
                        Text(importError!!, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val res = keyManager.importPrivateKey(importKeyInput)
                    if (res.isSuccess) {
                        onPubKeyUpdated(res.getOrThrow().second)
                        showImportDialog = false
                        Toast.makeText(context, "SSH Key imported successfully!", Toast.LENGTH_SHORT).show()
                    } else {
                        importError = res.exceptionOrNull()?.message ?: "Failed to parse key"
                    }
                }) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Regenerate Key Dialog
    if (showRegenerateDialog) {
        AlertDialog(
            onDismissRequest = { showRegenerateDialog = false },
            title = { Text("Generate New SSH Key?") },
            text = {
                Text("This generates a new Ed25519 client identity keypair. Remember to update /etc/dropbear/initramfs/authorized_keys on your laptop.")
            },
            confirmButton = {
                Button(onClick = {
                    val newPair = keyManager.regenerateKeyPair()
                    onPubKeyUpdated(newPair.second)
                    showRegenerateDialog = false
                    Toast.makeText(context, "New SSH key generated!", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Generate")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRegenerateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun PasswordEditDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var pass1 by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set LUKS Disk Passphrase") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Password is stored encrypted in Android KeyStore and cannot be viewed once entered.",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
                OutlinedTextField(
                    value = pass1,
                    onValueChange = { pass1 = it; errorMsg = null },
                    label = { Text("Disk Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pass2,
                    onValueChange = { pass2 = it; errorMsg = null },
                    label = { Text("Confirm Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (errorMsg != null) {
                    Text(errorMsg!!, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (pass1.isEmpty()) {
                    errorMsg = "Password cannot be empty"
                } else if (pass1 != pass2) {
                    errorMsg = "Passwords do not match"
                } else {
                    onSave(pass1)
                }
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun LogsTab(onCopy: (String, String) -> Unit) {
    val logEvents by AppLogger.events.collectAsState()
    var selectedLogLevel by remember { mutableStateOf<LogLevel?>(null) }
    val listState = rememberLazyListState()

    val filteredLogs = remember(logEvents, selectedLogLevel) {
        if (selectedLogLevel == null) logEvents else logEvents.filter { it.level.ordinal >= selectedLogLevel!!.ordinal }
    }

    LaunchedEffect(filteredLogs.size) {
        if (filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Console Activity Log", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = {
                    val exported = AppLogger.exportLogs(selectedLogLevel ?: LogLevel.DEBUG)
                    onCopy("Unlocker Logs", exported)
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Logs", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = { AppLogger.clear() }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear Logs", modifier = Modifier.size(18.dp))
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = selectedLogLevel == null,
                onClick = { selectedLogLevel = null },
                label = { Text("ALL", fontSize = 10.sp) }
            )
            FilterChip(
                selected = selectedLogLevel == LogLevel.INFO,
                onClick = { selectedLogLevel = LogLevel.INFO },
                label = { Text("INFO", fontSize = 10.sp) }
            )
            FilterChip(
                selected = selectedLogLevel == LogLevel.WARN,
                onClick = { selectedLogLevel = LogLevel.WARN },
                label = { Text("WARN", fontSize = 10.sp) }
            )
            FilterChip(
                selected = selectedLogLevel == LogLevel.ERROR,
                onClick = { selectedLogLevel = LogLevel.ERROR },
                label = { Text("ERROR", fontSize = 10.sp) }
            )
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0D0D0D)),
            shape = RoundedCornerShape(10.dp)
        ) {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    items(filteredLogs, key = { it.id }) { log ->
                        val color = when (log.level) {
                            LogLevel.DEBUG -> Color(0xFF78909C)
                            LogLevel.INFO -> if (log.message.contains("[SUCCESS]") || log.message.contains("Unlocked")) Color(0xFF81C784) else Color(0xFFB0BEC5)
                            LogLevel.WARN -> Color(0xFFFFB74D)
                            LogLevel.ERROR -> Color(0xFFE57373)
                        }

                        Text(
                            text = "[${log.formatDisplayTime()}] [${log.level.name}] ${log.message}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = color
                        )
                    }
                }
            }
        }
    }
}
