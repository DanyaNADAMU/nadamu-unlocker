package mu.nada.unlocker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import mu.nada.unlocker.data.AutonomyMode
import mu.nada.unlocker.log.AppLogger
import mu.nada.unlocker.security.HostKeyManager

class BootAndUnlockReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return

        AppLogger.i("BootReceiver", "Received broadcast action: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val hostKeyManager = HostKeyManager(context)
            val mode = hostKeyManager.getAutonomyMode()
            if (mode == AutonomyMode.AUTO || mode == AutonomyMode.SEMI_AUTO) {
                AppLogger.i("BootReceiver", "Autonomy mode is $mode. Restoring UnlockForegroundService.")
                UnlockForegroundService.start(context)
            }
        }
    }
}
