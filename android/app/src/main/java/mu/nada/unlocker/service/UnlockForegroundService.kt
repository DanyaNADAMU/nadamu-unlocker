package mu.nada.unlocker.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import mu.nada.unlocker.MainActivity
import mu.nada.unlocker.R
import mu.nada.unlocker.data.AutonomyMode
import mu.nada.unlocker.data.DiscoveredDevice
import mu.nada.unlocker.data.KeyManager
import mu.nada.unlocker.data.NetworkChannel
import mu.nada.unlocker.data.NetworkScanner
import mu.nada.unlocker.log.AppLogger
import mu.nada.unlocker.security.HostKeyManager
import mu.nada.unlocker.security.TofuDecision
import mu.nada.unlocker.ssh.SshUnlocker
import mu.nada.unlocker.ssh.UnlockResult
import java.util.concurrent.atomic.AtomicLong

class UnlockForegroundService : Service() {

    companion object {
        private const val TAG = "UnlockService"
        const val CHANNEL_SERVICE = "nadamu_service_channel"
        const val CHANNEL_ALERTS = "nadamu_alerts_channel"

        const val NOTIFICATION_ID_SERVICE = 1001
        const val NOTIFICATION_ID_PROMPT = 1002
        const val NOTIFICATION_ID_RESULT = 1003

        const val ACTION_START = "mu.nada.unlocker.action.START_SERVICE"
        const val ACTION_STOP = "mu.nada.unlocker.action.STOP_SERVICE"
        const val ACTION_TRIGGER_UNLOCK = "mu.nada.unlocker.action.TRIGGER_UNLOCK"
        const val ACTION_DISMISS_PROMPT = "mu.nada.unlocker.action.DISMISS_PROMPT"

        const val EXTRA_TARGET_IP = "extra_target_ip"
        const val EXTRA_TARGET_PORT = "extra_target_port"

        fun start(context: Context) {
            try {
                val intent = Intent(context, UnlockForegroundService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to start UnlockForegroundService", e)
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, UnlockForegroundService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to stop UnlockForegroundService", e)
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var keyManager: KeyManager
    private lateinit var hostKeyManager: HostKeyManager
    private val scanner = NetworkScanner()
    private val unlocker = SshUnlocker()

    private val lastTriggerTime = AtomicLong(0)
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var eventReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        try {
            KeyManager.initBouncyCastle()
            keyManager = KeyManager(this)
            hostKeyManager = HostKeyManager(this)
            createNotificationChannels()
            startForegroundWithNotification()

            registerEventReceivers()
            registerNetworkCallbacks()

            AppLogger.i(TAG, "UnlockForegroundService created. Mode: ${hostKeyManager.getAutonomyMode().displayName}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error in UnlockForegroundService.onCreate", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_STOP -> {
                    AppLogger.i(TAG, "Stopping service via ACTION_STOP")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                    }
                    stopSelf()
                    return START_NOT_STICKY
                }
                ACTION_TRIGGER_UNLOCK -> {
                    val ip = intent.getStringExtra(EXTRA_TARGET_IP)
                    val port = intent.getIntExtra(EXTRA_TARGET_PORT, 22)
                    AppLogger.i(TAG, "Manual trigger unlock requested from notification/tile: $ip:$port")
                    cancelNotification(NOTIFICATION_ID_PROMPT)
                    serviceScope.launch {
                        if (ip != null) {
                            performUnlock(ip, port)
                        } else {
                            handleNetworkEvent("Manual Trigger", force = true)
                        }
                    }
                }
                ACTION_DISMISS_PROMPT -> {
                    cancelNotification(NOTIFICATION_ID_PROMPT)
                }
                else -> {
                    val mode = hostKeyManager.getAutonomyMode()
                    if (mode == AutonomyMode.MANUAL) {
                        AppLogger.d(TAG, "Service started but mode is MANUAL; stopping service.")
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    startForegroundWithNotification()
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error in onStartCommand", e)
        }
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        try {
            val mode = hostKeyManager.getAutonomyMode()
            val text = when (mode) {
                AutonomyMode.AUTO -> "Auto-Unlock active • Monitoring USB, Hotspot & Wi-Fi"
                AutonomyMode.SEMI_AUTO -> "Semi-Auto active • Ready to prompt on network events"
                AutonomyMode.MANUAL -> "Ready on demand"
            }

            val openAppIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, CHANNEL_SERVICE)
                .setSmallIcon(R.drawable.app_logo)
                .setContentTitle("Nadamu Unlocker")
                .setContentText(text)
                .setContentIntent(openAppIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID_SERVICE,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID_SERVICE, notification)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to startForegroundWithNotification", e)
        }
    }

    private fun registerEventReceivers() {
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction("android.hardware.usb.action.USB_STATE")
                addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
                addAction("android.net.wifi.STATE_CHANGE")
            }

            eventReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val action = intent?.action ?: return
                    val eventName = when (action) {
                        Intent.ACTION_USER_PRESENT -> "Screen Unlocked"
                        "android.hardware.usb.action.USB_STATE" -> {
                            val connected = intent.getBooleanExtra("connected", false)
                            if (!connected) return
                            "USB Connected"
                        }
                        "android.net.wifi.WIFI_AP_STATE_CHANGED" -> "Hotspot State Changed"
                        else -> action
                    }

                    // Check channel enablements before triggering
                    if (action == "android.hardware.usb.action.USB_STATE" && !hostKeyManager.isChannelEnabled(NetworkChannel.USB)) {
                        return
                    }
                    if (action == "android.net.wifi.WIFI_AP_STATE_CHANGED" && !hostKeyManager.isChannelEnabled(NetworkChannel.HOTSPOT)) {
                        return
                    }

                    AppLogger.d(TAG, "Received system event: $eventName")
                    serviceScope.launch {
                        handleNetworkEvent(eventName)
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.registerReceiver(
                    this,
                    eventReceiver!!,
                    filter,
                    ContextCompat.RECEIVER_EXPORTED
                )
            } else {
                registerReceiver(eventReceiver, filter)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to register event receivers", e)
        }
    }

    private fun registerNetworkCallbacks() {
        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    AppLogger.d(TAG, "Network became available")
                    serviceScope.launch {
                        handleNetworkEvent("Network Connected")
                    }
                }
            }

            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to register network callback", e)
        }
    }

    /**
     * Core handler triggered by screen unlock, USB, Hotspot, or Wi-Fi events.
     */
    private suspend fun handleNetworkEvent(triggerReason: String, force: Boolean = false) {
        val mode = hostKeyManager.getAutonomyMode()
        if (mode == AutonomyMode.MANUAL && !force) return

        val now = System.currentTimeMillis()
        if (!force && (now - lastTriggerTime.get()) < 4000) {
            AppLogger.d(TAG, "Debounced trigger: $triggerReason (too frequent)")
            return
        }
        lastTriggerTime.set(now)

        val savedPass = keyManager.getSavedPassword()
        if (savedPass.isNullOrEmpty()) {
            AppLogger.w(TAG, "Cannot auto-unlock: No password saved in app")
            return
        }

        AppLogger.i(TAG, "Processing trigger: $triggerReason (Mode: ${mode.name}). Fast discovering target...")

        val target = scanner.fastDiscovery(hostKeyManager = hostKeyManager) ?: return
        AppLogger.i(TAG, "Found target: ${target.ip}:${target.port} (${target.interfaceName})")

        when (mode) {
            AutonomyMode.AUTO -> {
                performUnlock(target.ip, target.port, target.channel)
            }
            AutonomyMode.SEMI_AUTO -> {
                showSemiAutoPromptNotification(target)
            }
            AutonomyMode.MANUAL -> {
                if (force) performUnlock(target.ip, target.port, target.channel)
            }
        }
    }

    private suspend fun performUnlock(
        targetIp: String,
        targetPort: Int = 22,
        channel: NetworkChannel = NetworkChannel.LAN
    ) {
        val password = keyManager.getSavedPassword() ?: return
        val privKeyPem = keyManager.getPrivateKeyPem()

        AppLogger.i(TAG, "Auto-unlocking $targetIp:$targetPort (${channel.displayName})...")

        val result = unlocker.unlock(
            host = targetIp,
            port = targetPort,
            password = password,
            privateKeyPem = privKeyPem,
            mapperTarget = hostKeyManager.getMapperTarget(),
            pollTimeoutSeconds = hostKeyManager.getPollTimeoutSeconds(),
            hostKeyManager = hostKeyManager,
            tofuPrompt = { _, _, fp, _, isMismatch ->
                if (isMismatch) {
                    showMitMAlertNotification(targetIp, fp)
                    TofuDecision.REJECT
                } else {
                    // In background auto mode, we do NOT automatically pin unknown keys without user approval
                    showUntrustedHostNotification(targetIp, fp)
                    TofuDecision.REJECT
                }
            }
        )

        when (result) {
            is UnlockResult.Success -> {
                hostKeyManager.setLastIp(channel, targetIp)
                vibrateSuccess()
                showSuccessNotification(targetIp, result.message)
            }
            is UnlockResult.Failure -> {
                AppLogger.w(TAG, "Background unlock failed: ${result.error}")
            }
        }
    }

    private fun showSemiAutoPromptNotification(target: DiscoveredDevice) {
        try {
            val unlockIntent = Intent(this, UnlockForegroundService::class.java).apply {
                action = ACTION_TRIGGER_UNLOCK
                putExtra(EXTRA_TARGET_IP, target.ip)
                putExtra(EXTRA_TARGET_PORT, target.port)
            }
            val unlockPendingIntent = PendingIntent.getService(
                this,
                1,
                unlockIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val dismissIntent = Intent(this, UnlockForegroundService::class.java).apply {
                action = ACTION_DISMISS_PROMPT
            }
            val dismissPendingIntent = PendingIntent.getService(
                this,
                2,
                dismissIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val openAppIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.app_logo)
                .setContentTitle("🔓 Laptop Detected (${target.channel.name})")
                .setContentText("Target found on ${target.ip}:${target.port}. Unlock now?")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent)
                .addAction(R.drawable.app_logo, "Unlock Now", unlockPendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", dismissPendingIntent)
                .build()

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID_PROMPT, notification)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to show semi-auto prompt notification", e)
        }
    }

    private fun showSuccessNotification(targetIp: String, message: String) {
        try {
            val openAppIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.app_logo)
                .setContentTitle("🎉 Laptop Unlocked!")
                .setContentText("Successfully unlocked LUKS disk on $targetIp.")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent)
                .build()

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID_RESULT, notification)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to show success notification", e)
        }
    }

    private fun showMitMAlertNotification(targetIp: String, fingerprint: String) {
        try {
            val openAppIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.app_logo)
                .setContentTitle("⚠️ MitM Alert: Untrusted Host Key")
                .setContentText("Host key on $targetIp does NOT match your trusted laptop! Connection aborted.")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent)
                .build()

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID_RESULT, notification)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to show MitM alert notification", e)
        }
    }

    private fun showUntrustedHostNotification(targetIp: String, fingerprint: String) {
        try {
            val openAppIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.app_logo)
                .setContentTitle("🔑 New Host Key Detected")
                .setContentText("Open Nadamu Unlocker to verify and trust this laptop key ($targetIp).")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent)
                .build()

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID_RESULT, notification)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to show untrusted host notification", e)
        }
    }

    private fun vibrateSuccess() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100, 80, 150), -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(longArrayOf(0, 100, 80, 150), -1)
                }
            }
        } catch (_: Exception) {}
    }

    private fun cancelNotification(id: Int) {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(id)
        } catch (_: Exception) {}
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "Background Service Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows the active background monitoring state of Nadamu Unlocker"
                setShowBadge(false)
            }

            val alertsChannel = NotificationChannel(
                CHANNEL_ALERTS,
                "Unlock Notifications & Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies about laptop detection, unlock status, and security alerts"
                enableVibration(true)
            }

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(serviceChannel)
            manager.createNotificationChannel(alertsChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        try {
            eventReceiver?.let { unregisterReceiver(it) }
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        AppLogger.i(TAG, "UnlockForegroundService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
