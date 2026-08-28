package mu.nada.unlocker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import mu.nada.unlocker.data.DiscoveredDevice
import mu.nada.unlocker.data.KeyManager
import mu.nada.unlocker.data.NetworkScanner
import mu.nada.unlocker.ssh.SshUnlocker
import mu.nada.unlocker.ssh.UnlockResult

class MainActivity : ComponentActivity() {

    private lateinit var keyManager: KeyManager
    private val scanner = NetworkScanner()
    private val unlocker = SshUnlocker()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keyManager = KeyManager(this)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF64B5F6),
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    UnlockScreen(
                        keyManager = keyManager,
                        scanner = scanner,
                        unlocker = unlocker,
                        onCopyKey = { key ->
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Nadamu SSH Key", key))
                            Toast.makeText(this, "Key copied to clipboard!", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnlockScreen(
    keyManager: KeyManager,
    scanner: NetworkScanner,
    unlocker: SshUnlocker,
    onCopyKey: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf(keyManager.getSavedPassword() ?: "") }
    var isScanning by remember { mutableStateOf(false) }
    var isUnlocking by remember { mutableStateOf(false) }
    var discoveredDevices by remember { mutableStateOf<List<DiscoveredDevice>>(emptyList()) }
    var logs by remember { mutableStateOf(listOf("Ready. Connect via Wi-Fi Hotspot, same LAN, or USB.")) }

    fun addLog(msg: String) {
        logs = (logs + msg).takeLast(20)
    }

    LaunchedEffect(Unit) {
        val ifaces = scanner.getTetheringInterfaces()
        if (ifaces.isNotEmpty()) {
            addLog("Detected interfaces: ${ifaces.joinToString { it.name }}")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "NADAMU UNLOCKER",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.primary
            )
            IconButton(onClick = {
                scope.launch {
                    isScanning = true
                    addLog("Scanning for laptop across active networks (Wi-Fi/LAN/USB)...")
                    discoveredDevices = scanner.scanSubnetForLuks()
                    if (discoveredDevices.isEmpty()) {
                        addLog("No Dropbear devices found on active networks.")
                    } else {
                        discoveredDevices.forEach { dev ->
                            addLog("Found device: ${dev.ip}:${dev.port} (${dev.interfaceName})")
                        }
                    }
                    isScanning = false
                }
            }) {
                Icon(Icons.Default.Refresh, contentDescription = "Scan")
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
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) }
        )

        // Action Button: Fast Unlock
        Button(
            onClick = {
                scope.launch {
                    if (password.isEmpty()) {
                        addLog("Error: Please enter password first.")
                        return@launch
                    }
                    isUnlocking = true
                    addLog("Starting auto-discovery & unlock...")

                    val targets = if (discoveredDevices.isNotEmpty()) {
                        discoveredDevices
                    } else {
                        scanner.scanSubnetForLuks()
                    }

                    if (targets.isEmpty()) {
                        addLog("Failed: No laptop found on Wi-Fi / LAN / USB network.")
                    } else {
                        val privKeyPem = keyManager.getPrivateKeyPem()
                        targets.forEach { target ->
                            addLog("Unlocking ${target.ip}...")
                            when (val res = unlocker.unlock(target.ip, target.port, password, privKeyPem)) {
                                is UnlockResult.Success -> addLog("[SUCCESS] ${res.message}")
                                is UnlockResult.Failure -> addLog("[FAIL] ${res.error}")
                            }
                        }
                    }
                    isUnlocking = false
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            enabled = !isUnlocking
        ) {
            if (isUnlocking || isScanning) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Processing...")
            } else {
                Icon(Icons.Default.Usb, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("UNLOCK LAPTOP (LOCAL)", fontWeight = FontWeight.Bold)
            }
        }

        // SSH Key Section
        val pubKey = remember { keyManager.getPublicKeyOpenSsh() }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Client SSH Public Key:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { onCopyKey(pubKey) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp))
                    }
                }
                Text(
                    text = pubKey,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    color = Color.Gray
                )
            }
        }

        // Live Console Log
        Text("Console Activity Log:", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(containerColor = Color.Black)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(logs) { log ->
                    Text(
                        text = log,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = when {
                            log.contains("[SUCCESS]") -> Color(0xFF81C784)
                            log.contains("[FAIL]") || log.contains("Error") -> Color(0xFFE57373)
                            else -> Color(0xFFB0BEC5)
                        }
                    )
                }
            }
        }
    }
}
