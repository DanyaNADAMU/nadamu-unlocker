package mu.nada.unlocker.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import mu.nada.unlocker.MainActivity
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
            Toast.makeText(this, "Please save LUKS password in Unlocker Settings first", Toast.LENGTH_SHORT).show()
            updateTileState(Tile.STATE_INACTIVE, "No password")
            return
        }

        // Cancel background scans when user taps tile
        UnlockForegroundService.cancelActiveBackgroundJob("Quick Settings Tile tapped")

        if (hostKeyManager.isBiometricUnlockRequired()) {
            val intent = Intent(this, MainActivity::class.java).apply {
                action = UnlockForegroundService.ACTION_CONFIRM_BIOMETRIC_UNLOCK
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val pendingIntent = PendingIntent.getActivity(
                        this,
                        0,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    startActivityAndCollapse(pendingIntent)
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(intent)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to start activity from tile for biometric confirmation", e)
            }
            updateTileState(Tile.STATE_INACTIVE, "Auth required")
            return
        }

        updateTileState(Tile.STATE_UNAVAILABLE, "Unlocking...")
        UnlockForegroundService.isUserJobRunning = true

        scope.launch {
            try {
                AppLogger.i(TAG, "Quick Settings Tile tapped. Fast discovering target...")
                val target = scanner.fastDiscovery(hostKeyManager = hostKeyManager)

                if (target == null) {
                    AppLogger.w(TAG, "Tile unlock: No Dropbear laptop found on active channels")
                    launch(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "No trusted laptop found on network", Toast.LENGTH_SHORT).show()
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
                        TofuDecision.REJECT
                    }
                )

                launch(Dispatchers.Main) {
                    when (result) {
                        is UnlockResult.Success -> {
                            hostKeyManager.setLastIp(target.channel, target.ip)
                            vibrateSuccess()
                            Toast.makeText(applicationContext, "🎉 ${target.label} Unlocked!", Toast.LENGTH_LONG).show()
                            updateTileState(Tile.STATE_ACTIVE, "Unlocked")
                        }
                        is UnlockResult.Failure -> {
                            Toast.makeText(applicationContext, "Unlock failed: ${result.error}", Toast.LENGTH_SHORT).show()
                            updateTileState(Tile.STATE_INACTIVE, "Failed")
                        }
                    }
                }
            } finally {
                UnlockForegroundService.isUserJobRunning = false
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
