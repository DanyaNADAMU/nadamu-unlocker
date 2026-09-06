package mu.nada.unlocker.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import mu.nada.unlocker.data.DiscoveryMode
import mu.nada.unlocker.data.NetworkChannel
import mu.nada.unlocker.log.AppLogger
import org.json.JSONArray
import org.json.JSONObject

class HostKeyManager(private val context: Context) {

    companion object {
        private const val TAG = "HostKeyManager"
        private const val PREFS_NAME = "nadamu_host_keys_sec"
        private const val FALLBACK_PREFS_NAME = "nadamu_host_keys"
        private const val KEY_TRUSTED_KEYS = "trusted_host_keys_json"
        private const val PREFIX_CACHE_IP = "cache_ip_"
        private const val KEY_CHANNEL_PRIORITY = "channel_priority_order"
        private const val PREFIX_CHANNEL_ENABLED = "channel_enabled_"
        private const val KEY_DISCOVERY_MODE = "discovery_mode"
        private const val KEY_MAPPER_TARGET = "mapper_target"
        private const val KEY_POLL_TIMEOUT = "poll_timeout_sec"
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

    // --- Device-Centric Trusted Host Keys ---

    fun getTrustedKeys(): List<TrustedHostKey> {
        val jsonStr = prefs.getString(KEY_TRUSTED_KEYS, null) ?: return emptyList()
        val result = mutableListOf<TrustedHostKey>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                result.add(
                    TrustedHostKey(
                        fingerprint = obj.getString("fingerprint"),
                        label = obj.optString("label", "Laptop"),
                        addedTimestamp = obj.optLong("addedTimestamp", System.currentTimeMillis())
                    )
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to parse trusted keys JSON", e)
        }
        return result
    }

    fun hasAnyTrustedKeys(): Boolean = getTrustedKeys().isNotEmpty()

    fun isFingerprintTrusted(fingerprint: String): Boolean {
        val trimmed = fingerprint.trim()
        return getTrustedKeys().any { it.fingerprint.trim().equals(trimmed, ignoreCase = true) }
    }

    fun trustFingerprint(fingerprint: String, label: String = "Laptop") {
        val current = getTrustedKeys().toMutableList()
        val trimmed = fingerprint.trim()
        current.removeAll { it.fingerprint.trim().equals(trimmed, ignoreCase = true) }
        current.add(0, TrustedHostKey(fingerprint = trimmed, label = label.ifBlank { "Laptop" }))
        saveTrustedKeys(current)
        AppLogger.i(TAG, "Trusted host key fingerprint: $trimmed ($label)")
    }

    fun untrustFingerprint(fingerprint: String) {
        val current = getTrustedKeys().toMutableList()
        val trimmed = fingerprint.trim()
        current.removeAll { it.fingerprint.trim().equals(trimmed, ignoreCase = true) }
        saveTrustedKeys(current)
        AppLogger.i(TAG, "Untrusted host key fingerprint: $trimmed")
    }

    fun clearAllTrustedKeys() {
        prefs.edit().remove(KEY_TRUSTED_KEYS).apply()
        AppLogger.i(TAG, "Cleared all trusted host keys")
    }

    private fun saveTrustedKeys(keys: List<TrustedHostKey>) {
        val array = JSONArray()
        for (k in keys) {
            val obj = JSONObject()
            obj.put("fingerprint", k.fingerprint)
            obj.put("label", k.label)
            obj.put("addedTimestamp", k.addedTimestamp)
            array.put(obj)
        }
        prefs.edit().putString(KEY_TRUSTED_KEYS, array.toString()).apply()
    }

    // --- Channel Priority & Enablement Settings ---

    fun getChannelPriority(): List<NetworkChannel> {
        val saved = prefs.getString(KEY_CHANNEL_PRIORITY, null)
        if (saved.isNullOrBlank()) {
            return listOf(NetworkChannel.USB, NetworkChannel.HOTSPOT, NetworkChannel.LAN)
        }
        return try {
            val names = saved.split(",")
            val parsed = names.mapNotNull { name ->
                try { NetworkChannel.valueOf(name.trim()) } catch (_: Exception) { null }
            }
            if (parsed.size == NetworkChannel.values().size) parsed else listOf(NetworkChannel.USB, NetworkChannel.HOTSPOT, NetworkChannel.LAN)
        } catch (_: Exception) {
            listOf(NetworkChannel.USB, NetworkChannel.HOTSPOT, NetworkChannel.LAN)
        }
    }

    fun setChannelPriority(priority: List<NetworkChannel>) {
        val str = priority.joinToString(",") { it.name }
        prefs.edit().putString(KEY_CHANNEL_PRIORITY, str).apply()
        AppLogger.d(TAG, "Updated channel priority: $str")
    }

    fun isChannelEnabled(channel: NetworkChannel): Boolean {
        return prefs.getBoolean("$PREFIX_CHANNEL_ENABLED${channel.name}", true)
    }

    fun setChannelEnabled(channel: NetworkChannel, enabled: Boolean) {
        prefs.edit().putBoolean("$PREFIX_CHANNEL_ENABLED${channel.name}", enabled).apply()
        AppLogger.d(TAG, "Channel ${channel.name} enabled: $enabled")
    }

    // --- Discovery Mode ---

    fun getDiscoveryMode(): DiscoveryMode {
        val name = prefs.getString(KEY_DISCOVERY_MODE, DiscoveryMode.FAST.name)
        return try {
            DiscoveryMode.valueOf(name ?: DiscoveryMode.FAST.name)
        } catch (_: Exception) {
            DiscoveryMode.FAST
        }
    }

    fun setDiscoveryMode(mode: DiscoveryMode) {
        prefs.edit().putString(KEY_DISCOVERY_MODE, mode.name).apply()
        AppLogger.d(TAG, "Discovery mode set to: ${mode.name}")
    }

    // --- IP Caching ---

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

    // --- Mapper & Timeout Settings ---

    fun getMapperTarget(): String {
        return prefs.getString(KEY_MAPPER_TARGET, "auto") ?: "auto"
    }

    fun setMapperTarget(target: String) {
        prefs.edit().putString(KEY_MAPPER_TARGET, target).apply()
    }

    fun getPollTimeoutSeconds(): Int {
        return prefs.getInt(KEY_POLL_TIMEOUT, 15)
    }

    fun setPollTimeoutSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_POLL_TIMEOUT, seconds.coerceIn(3, 60)).apply()
    }
}
