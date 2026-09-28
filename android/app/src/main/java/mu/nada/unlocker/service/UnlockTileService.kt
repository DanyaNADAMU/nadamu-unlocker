package mu.nada.unlocker.service

import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mu.nada.unlocker.R
import mu.nada.unlocker.data.KeyManager
import mu.nada.unlocker.data.NetworkScanner
import mu.nada.unlocker.log.AppLogger
import mu.nada.unlocker.security.HostKeyManager
import mu.nada.unlocker.security.TofuDecision
import mu.nada.unlocker.ssh.SshUnlocker
import mu.nada.unlocker.ssh.UnlockResult

@RequiresApi(Build.VERSION_CODES.N)
class UnlockTileService : TileService() {

    companion object {
        private const val TAG = "UnlockTileService"
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private lateinit var keyManager: KeyManager
    private lateinit var hostKeyManager: HostKeyManager
    private val scanner = NetworkScanner()
    private val unlocker = SshUnlocker()

    override fun onCreate() {
        super.onCreate()
        KeyManager.initBouncyCastle()
        keyManager = KeyManager(this)
        hostKeyManager = HostKeyManager(this)
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState(Tile.STATE_INACTIVE, "Tap to unlock")
    }

    override fun onClick() {
        super.onClick()

        val password = keyManager.getSavedPassword()
        if (password.isNullOrEmpty()) {
            Toast.makeText(this, "Please save LUKS password in Unlocker first", Toast.LENGTH_SHORT).show()
            updateTileState(Tile.STATE_INACTIVE, "No password")
            return
        }

        updateTileState(Tile.STATE_UNAVAILABLE, "Unlocking...")

        scope.launch {
            AppLogger.i(TAG, "Quick Settings Tile tapped. Fast discovering target...")
            val target = scanner.fastDiscovery(hostKeyManager = hostKeyManager)

            if (target == null) {
                AppLogger.w(TAG, "Tile unlock: No Dropbear laptop found on active channels")
                launch(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "No laptop found on network", Toast.LENGTH_SHORT).show()
                    updateTileState(Tile.STATE_INACTIVE, "Not found")
                }
                return@launch
            }

            val privKeyPem = keyManager.getPrivateKeyPem()
            val result = unlocker.unlock(
                host = target.ip,
                port = target.port,
                password = password,
                privateKeyPem = privKeyPem,
                mapperTarget = hostKeyManager.getMapperTarget(),
                pollTimeoutSeconds = hostKeyManager.getPollTimeoutSeconds(),
                hostKeyManager = hostKeyManager,
                tofuPrompt = { _, _, _, _, _ ->
                    // Reject unknown keys from quick settings tile (must trust in app)
                    TofuDecision.REJECT
                }
            )

            launch(Dispatchers.Main) {
                when (result) {
                    is UnlockResult.Success -> {
                        hostKeyManager.setLastIp(target.channel, target.ip)
                        vibrateSuccess()
                        Toast.makeText(applicationContext, "🎉 Laptop Unlocked!", Toast.LENGTH_LONG).show()
                        updateTileState(Tile.STATE_ACTIVE, "Unlocked")
                    }
                    is UnlockResult.Failure -> {
                        Toast.makeText(applicationContext, "Unlock failed: ${result.error}", Toast.LENGTH_SHORT).show()
                        updateTileState(Tile.STATE_INACTIVE, "Failed")
                    }
                }
            }
        }
    }

    private fun updateTileState(state: Int, subtitle: String) {
        val tile = qsTile ?: return
        tile.state = state
        tile.label = "Unlock Laptop"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = subtitle
        }
        tile.icon = Icon.createWithResource(this, R.drawable.app_logo)
        tile.updateTile()
    }

    private fun vibrateSuccess() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100, 80, 150), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 100, 80, 150), -1)
            }
        } catch (_: Exception) {}
    }
}
