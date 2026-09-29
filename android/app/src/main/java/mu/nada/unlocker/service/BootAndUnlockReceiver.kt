package mu.nada.unlocker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import mu.nada.unlocker.log.AppLogger
import mu.nada.unlocker.security.HostKeyManager

class BootAndUnlockReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return

        AppLogger.i("BootReceiver", "Received broadcast action: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val hostKeyManager = HostKeyManager(context)
            if (hostKeyManager.isAnyTriggerEnabled()) {
                AppLogger.i("BootReceiver", "Background triggers are enabled. Restoring UnlockForegroundService.")
                UnlockForegroundService.start(context)
            }
        }
    }
}
