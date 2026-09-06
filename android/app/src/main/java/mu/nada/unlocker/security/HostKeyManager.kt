package mu.nada.unlocker.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import mu.nada.unlocker.data.NetworkChannel
import mu.nada.unlocker.log.AppLogger

class HostKeyManager(private val context: Context) {

    companion object {
        private const val TAG = "HostKeyManager"
        private const val PREFS_NAME = "nadamu_host_keys_sec"
        private const val FALLBACK_PREFS_NAME = "nadamu_host_keys"
        private const val PREFIX_HOST_KEY = "host_fp_"
        private const val PREFIX_CACHE_IP = "cache_ip_"
    }

    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            AppLogger.w(TAG, "EncryptedSharedPreferences unavailable; falling back to standard private prefs: ${e.message}")
            context.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    fun hostKeyToKey(host: String, port: Int): String = "$PREFIX_HOST_KEY$host:$port"

    fun getPinnedFingerprint(host: String, port: Int): String? {
        return prefs.getString(hostKeyToKey(host, port), null)
    }

    fun isHostPinned(host: String, port: Int): Boolean {
        return getPinnedFingerprint(host, port) != null
    }

    fun pinHostKey(host: String, port: Int, fingerprint: String) {
        prefs.edit().putString(hostKeyToKey(host, port), fingerprint).apply()
        AppLogger.i(TAG, "Pinned host key for $host:$port -> $fingerprint")
    }

    fun unpinHostKey(host: String, port: Int) {
        prefs.edit().remove(hostKeyToKey(host, port)).apply()
        AppLogger.i(TAG, "Unpinned host key for $host:$port")
    }

    fun getAllPinnedHosts(): Map<String, String> {
        val all = prefs.all
        val result = mutableMapOf<String, String>()
        for ((key, value) in all) {
            if (key.startsWith(PREFIX_HOST_KEY) && value is String) {
                val hostPort = key.removePrefix(PREFIX_HOST_KEY)
                result[hostPort] = value
            }
        }
        return result
    }

    fun clearAllPinnedHosts() {
        val editor = prefs.edit()
        for (key in prefs.all.keys) {
            if (key.startsWith(PREFIX_HOST_KEY)) {
                editor.remove(key)
            }
        }
        editor.apply()
        AppLogger.i(TAG, "Cleared all pinned host keys")
    }

    fun getLastIp(channel: NetworkChannel): String? {
        return prefs.getString("$PREFIX_CACHE_IP${channel.name}", null)
    }

    fun setLastIp(channel: NetworkChannel, ip: String) {
        prefs.edit().putString("$PREFIX_CACHE_IP${channel.name}", ip).apply()
        AppLogger.d(TAG, "Cached last IP for ${channel.name}: $ip")
    }

    fun clearCachedIps() {
        val editor = prefs.edit()
        for (channel in NetworkChannel.values()) {
            editor.remove("$PREFIX_CACHE_IP${channel.name}")
        }
        editor.apply()
        AppLogger.d(TAG, "Cleared all cached IPs")
    }
}
