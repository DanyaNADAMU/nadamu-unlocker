package mu.nada.unlocker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import mu.nada.unlocker.data.DiscoveredDevice
import mu.nada.unlocker.data.KeyManager
import mu.nada.unlocker.data.NetworkChannel
import mu.nada.unlocker.data.NetworkScanner
import mu.nada.unlocker.log.AppLogger
import mu.nada.unlocker.log.LogEvent
import mu.nada.unlocker.log.LogLevel
import mu.nada.unlocker.security.HostKeyManager
import mu.nada.unlocker.security.TofuDecision
import mu.nada.unlocker.ssh.SshUnlocker
import mu.nada.unlocker.ssh.UnlockResult

class MainActivity : ComponentActivity() {

    private lateinit var keyManager: KeyManager
    private lateinit var hostKeyManager: HostKeyManager
    private val scanner = NetworkScanner()
    private val unlocker = SshUnlocker()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KeyManager.initBouncyCastle()
        keyManager = KeyManager(this)
        hostKeyManager = HostKeyManager(this)

        AppLogger.i("MainActivity", "Nadamu Unlocker initialized. Ready.")

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
                    UnlockScreen(
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

data class TofuPromptRequest(
    val hostname: String,
    val port: Int,
    val fingerprint: String,
    val keyType: String,
    val deferred: CompletableDeferred<TofuDecision>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnlockScreen(
    keyManager: KeyManager,
    hostKeyManager: HostKeyManager,
    scanner: NetworkScanner,
    unlocker: SshUnlocker,
    onCopy: (String, String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var password by remember { mutableStateOf(keyManager.getSavedPassword() ?: "") }
    var showPassword by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }
    var isUnlocking by remember { mutableStateOf(false) }
    var discoveredDevices by remember { mutableStateOf<List<DiscoveredDevice>>(emptyList()) }

    var pubKey by remember { mutableStateOf(keyManager.getPublicKeyOpenSsh()) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showRegenerateDialog by remember { mutableStateOf(false) }
    var showPinnedHostsDialog by remember { mutableStateOf(false) }
    var importKeyInput by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }

    // TOFU Dialog State
    var tofuRequest by remember { mutableStateOf<TofuPromptRequest?>(null) }

    // Log filtering
    val logEvents by AppLogger.events.collectAsState()
    var selectedLogLevel by remember { mutableStateOf<LogLevel?>(LogLevel.INFO) }

    LaunchedEffect(Unit) {
        val ifaces = scanner.getEligibleInterfaces()
        if (ifaces.isNotEmpty()) {
            AppLogger.d("UnlockScreen", "Active network interfaces: ${ifaces.joinToString { it.name }}")
        }
    }

    // TOFU Dialog
    tofuRequest?.let { req ->
        AlertDialog(
            onDismissRequest = {
                req.deferred.complete(TofuDecision.REJECT)
                tofuRequest = null
            },
            icon = { Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Trust Host Key (TOFU)?", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "First time connecting to Dropbear on this target. Verify the host key fingerprint before sending disk password:",
                        fontSize = 13.sp
                    )
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Host: ${req.hostname}:${req.port}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("Key Type: ${req.keyType}", fontSize = 11.sp, color = Color.Gray)
                            SelectionContainer {
                                Text(
                                    "Fingerprint:\n${req.fingerprint}",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    req.deferred.complete(TofuDecision.TRUST_AND_PIN)
                    tofuRequest = null
                }) {
                    Text("Trust & Pin")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        req.deferred.complete(TofuDecision.TRUST_ONCE)
                        tofuRequest = null
                    }) {
                        Text("Trust Once")
                    }
                    TextButton(onClick = {
                        req.deferred.complete(TofuDecision.REJECT)
                        tofuRequest = null
                    }) {
                        Text("Reject", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        )
    }

    // Pinned Hosts Dialog
    if (showPinnedHostsDialog) {
        val pinnedHosts = hostKeyManager.getAllPinnedHosts()
        AlertDialog(
            onDismissRequest = { showPinnedHostsDialog = false },
            title = { Text("Pinned SSH Host Keys") },
            text = {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (pinnedHosts.isEmpty()) {
                        Text("No pinned hosts yet. Host keys are pinned via TOFU on first unlock.", fontSize = 13.sp, color = Color.Gray)
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(pinnedHosts.entries.toList()) { entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF2A2A2A), RoundedCornerShape(6.dp))
                                        .padding(8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(entry.key, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        Text(entry.value, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color.Gray)
                                    }
                                    IconButton(
                                        onClick = {
                                            val parts = entry.key.split(":")
                                            val host = parts[0]
                                            val port = parts.getOrNull(1)?.toIntOrNull() ?: 22
                                            hostKeyManager.unpinHostKey(host, port)
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Unpin", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPinnedHostsDialog = false }) {
                    Text("Close")
                }
            },
            dismissButton = {
                if (pinnedHosts.isNotEmpty()) {
                    TextButton(onClick = {
                        hostKeyManager.clearAllPinnedHosts()
                        showPinnedHostsDialog = false
                    }) {
                        Text("Clear All", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        )
    }

    // Import Key Dialog
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = {
                showImportDialog = false
                importKeyInput = ""
                importError = null
            },
            title = { Text("Import SSH Private Key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Paste your OpenSSH or PEM private key (e.g. Ed25519, RSA, ECDSA):",
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = importKeyInput,
                        onValueChange = {
                            importKeyInput = it
                            importError = null
                        },
                        label = { Text("Private Key") },
                        placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----\n...") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    )
                    if (importError != null) {
                        Text(
                            text = importError ?: "",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val res = keyManager.importPrivateKey(importKeyInput)
                    if (res.isSuccess) {
                        pubKey = res.getOrThrow().second
                        showImportDialog = false
                        importKeyInput = ""
                        importError = null
                        Toast.makeText(context, "SSH Key imported successfully!", Toast.LENGTH_SHORT).show()
                        AppLogger.i("KeyManager", "Imported custom SSH private key.")
                    } else {
                        importError = res.exceptionOrNull()?.message ?: "Failed to parse private key"
                    }
                }) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showImportDialog = false
                    importKeyInput = ""
                    importError = null
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Regenerate Key Dialog
    if (showRegenerateDialog) {
        AlertDialog(
            onDismissRequest = { showRegenerateDialog = false },
            title = { Text("Generate New Key?") },
            text = {
                Text(
                    "This generates a new Ed25519 keypair and replaces the current one. " +
                    "Remember to update /etc/dropbear/initramfs/authorized_keys on your laptop."
                )
            },
            confirmButton = {
                Button(onClick = {
                    val newPair = keyManager.regenerateKeyPair()
                    pubKey = newPair.second
                    showRegenerateDialog = false
                    Toast.makeText(context, "New Ed25519 key generated!", Toast.LENGTH_SHORT).show()
                    AppLogger.i("KeyManager", "Generated new Ed25519 SSH keypair.")
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

    // Function to perform unlock on a target host
    fun performUnlock(targetIp: String, targetPort: Int = 22, channel: NetworkChannel = NetworkChannel.LAN) {
        scope.launch {
            if (password.isEmpty()) {
                AppLogger.w("UnlockScreen", "Please enter LUKS disk password first.")
                Toast.makeText(context, "Please enter password", Toast.LENGTH_SHORT).show()
                return@launch
            }

            isUnlocking = true
            AppLogger.i("UnlockScreen", "Initiating unlock for $targetIp:$targetPort (${channel.displayName})...")

            val privKeyPem = keyManager.getPrivateKeyPem()
            val result = unlocker.unlock(
                host = targetIp,
                port = targetPort,
                password = password,
                privateKeyPem = privKeyPem,
                hostKeyManager = hostKeyManager,
                tofuPrompt = { host, port, fp, type ->
                    val def = CompletableDeferred<TofuDecision>()
                    tofuRequest = TofuPromptRequest(host, port, fp, type, def)
                    kotlinx.coroutines.runBlocking { def.await() }
                }
            )

            when (result) {
                is UnlockResult.Success -> {
                    hostKeyManager.setLastIp(channel, targetIp)
                    Toast.makeText(context, "LUKS Unlocked Successfully!", Toast.LENGTH_LONG).show()
                }
                is UnlockResult.Failure -> {
                    Toast.makeText(context, "Unlock failed: ${result.error}", Toast.LENGTH_SHORT).show()
                }
            }
            isUnlocking = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Top Header
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
                    modifier = Modifier.size(32.dp)
                )
                Text(
                    text = "UNLOCKER",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Row {
                IconButton(onClick = { showPinnedHostsDialog = true }) {
                    Icon(Icons.Default.VpnKey, contentDescription = "Pinned Host Keys", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = {
                    scope.launch {
                        isScanning = true
                        val activeIfaces = scanner.getEligibleInterfaces().joinToString { it.name }
                        AppLogger.i("NetworkScanner", "Full subnet scan on active networks ($activeIfaces)...")
                        discoveredDevices = scanner.scanSubnetForLuks()
                        if (discoveredDevices.isEmpty()) {
                            AppLogger.w("NetworkScanner", "No Dropbear targets found on port 22.")
                        } else {
                            discoveredDevices.forEach { dev ->
                                AppLogger.i("NetworkScanner", "Found device: ${dev.ip}:${dev.port} (${dev.interfaceName})")
                            }
                        }
                        isScanning = false
                    }
                }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Full Scan")
                }
            }
        }

        // Password Input
        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                keyManager.savePassword(it)
            },
            label = { Text("LUKS Disk Password") },
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        if (showPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = "Toggle password visibility"
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) }
        )

        // Action Button: Fast Auto-Unlock
        Button(
            onClick = {
                scope.launch {
                    if (password.isEmpty()) {
                        AppLogger.w("UnlockScreen", "Please enter LUKS disk password first.")
                        return@launch
                    }
                    isUnlocking = true
                    AppLogger.i("UnlockScreen", "Starting fast auto-discovery & unlock...")

                    val target = scanner.fastDiscovery(hostKeyManager = hostKeyManager)
                    if (target == null) {
                        AppLogger.e("UnlockScreen", "No laptop found (port 22 unreachable on Wi-Fi / Hotspot / USB).")
                        AppLogger.i("UnlockScreen", "Hint: Ensure laptop is in initramfs boot stage with Dropbear listening.")
                    } else {
                        performUnlock(target.ip, target.port, target.channel)
                    }
                    isUnlocking = false
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(12.dp),
            enabled = !isUnlocking && !isScanning
        ) {
            if (isUnlocking || isScanning) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Connecting...", fontWeight = FontWeight.Bold)
            } else {
                Icon(Icons.Default.Usb, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("FAST UNLOCK (AUTO)", fontWeight = FontWeight.Bold)
            }
        }

        // Discovered Devices Section (if any found via Full Scan)
        AnimatedVisibility(visible = discoveredDevices.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Discovered Targets (${discoveredDevices.size}):", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    discoveredDevices.forEach { dev ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF2A2A2A), RoundedCornerShape(8.dp))
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${dev.ip}:${dev.port}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Badge(containerColor = when (dev.channel) {
                                        NetworkChannel.USB -> Color(0xFF00ACC1)
                                        NetworkChannel.HOTSPOT -> Color(0xFF8E24AA)
                                        NetworkChannel.LAN -> Color(0xFF1E88E5)
                                    }) {
                                        Text(dev.channel.name, fontSize = 9.sp, color = Color.White)
                                    }
                                }
                                Text(dev.banner, fontSize = 10.sp, color = Color.Gray, maxLines = 1)
                            }
                            Button(
                                onClick = { performUnlock(dev.ip, dev.port, dev.channel) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                enabled = !isUnlocking
                            ) {
                                Text("Unlock", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }

        // SSH Key Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Client SSH Public Key:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Row {
                        IconButton(onClick = { showImportDialog = true }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Key, contentDescription = "Import Key", modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = { showRegenerateDialog = true }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Autorenew, contentDescription = "Generate New", modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = { onCopy("Nadamu SSH Key", pubKey) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp))
                        }
                    }
                }
                SelectionContainer {
                    Text(
                        text = pubKey,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        color = Color.Gray
                    )
                }
            }
        }

        // Structured Console Log Header & Filter Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Console Activity Log:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(
                    onClick = {
                        val exported = AppLogger.exportLogs(selectedLogLevel ?: LogLevel.DEBUG)
                        onCopy("Nadamu Logs", exported)
                    },
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Logs", modifier = Modifier.size(15.dp))
                }
                IconButton(
                    onClick = { AppLogger.clear() },
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear Logs", modifier = Modifier.size(15.dp))
                }
            }
        }

        // Filter chips: All/Debug, Info, Warn, Error
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

        // Console Log View
        val filteredLogs = remember(logEvents, selectedLogLevel) {
            if (selectedLogLevel == null) logEvents else logEvents.filter { it.level.ordinal >= selectedLogLevel!!.ordinal }
        }
        val listState = rememberLazyListState()

        LaunchedEffect(filteredLogs.size) {
            if (filteredLogs.isNotEmpty()) {
                listState.animateScrollToItem(filteredLogs.size - 1)
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0D0D0D))
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
                            LogLevel.INFO -> if (log.message.contains("[SUCCESS]")) Color(0xFF81C784) else Color(0xFFB0BEC5)
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
