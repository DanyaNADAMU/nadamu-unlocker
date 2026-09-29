package mu.nada.unlocker.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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
        private const val KEY_TRIGGER_USB = "trigger_event_usb"
        private const val KEY_TRIGGER_HOTSPOT = "trigger_event_hotspot"
        private const val KEY_TRIGGER_WIFI = "trigger_event_wifi"
        private const val KEY_TRIGGER_SCREEN_UNLOCK = "trigger_event_screen_unlock"
        private const val KEY_MAPPER_TARGET = "mapper_target"
        private const val KEY_POLL_TIMEOUT = "poll_timeout_sec"
        private const val KEY_TARGET_PORTS = "target_ports_csv"
        private const val KEY_BANNER_REGEX = "banner_regex_filter"
        private const val KEY_BIOMETRIC_UNLOCK = "biometric_unlock_required"
        private const val KEY_APP_LOCK = "app_lock_enabled"
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

    fun getLabelForFingerprint(fingerprint: String): String {
        val trimmed = fingerprint.trim()
        return getTrustedKeys().firstOrNull { it.fingerprint.trim().equals(trimmed, ignoreCase = true) }?.label ?: "Laptop"
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

    // --- Trigger Settings ---

    fun isTriggerUsbEnabled(): Boolean = prefs.getBoolean(KEY_TRIGGER_USB, true)
    fun setTriggerUsbEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TRIGGER_USB, enabled).apply()
        AppLogger.d(TAG, "Trigger USB enabled: $enabled")
    }

    fun isTriggerHotspotEnabled(): Boolean = prefs.getBoolean(KEY_TRIGGER_HOTSPOT, true)
    fun setTriggerHotspotEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TRIGGER_HOTSPOT, enabled).apply()
        AppLogger.d(TAG, "Trigger Hotspot enabled: $enabled")
    }

    fun isTriggerWifiEnabled(): Boolean = prefs.getBoolean(KEY_TRIGGER_WIFI, true)
    fun setTriggerWifiEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TRIGGER_WIFI, enabled).apply()
        AppLogger.d(TAG, "Trigger Wi-Fi/LAN enabled: $enabled")
    }

    fun isTriggerScreenUnlockEnabled(): Boolean = prefs.getBoolean(KEY_TRIGGER_SCREEN_UNLOCK, true)
    fun setTriggerScreenUnlockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TRIGGER_SCREEN_UNLOCK, enabled).apply()
        AppLogger.d(TAG, "Trigger Screen Unlock enabled: $enabled")
    }

    fun isAnyTriggerEnabled(): Boolean {
        return isTriggerUsbEnabled() || isTriggerHotspotEnabled() || isTriggerWifiEnabled() || isTriggerScreenUnlockEnabled()
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

    // --- Target Ports & Banner Regex Filters ---

    fun getTargetPorts(): List<Int> {
        val saved = prefs.getString(KEY_TARGET_PORTS, "22")
        if (saved.isNullOrBlank()) return listOf(22)
        val ports = saved.split(",").mapNotNull { it.trim().toIntOrNull() }
        return if (ports.isNotEmpty()) ports else listOf(22)
    }

    fun setTargetPorts(ports: List<Int>) {
        val str = ports.filter { it in 1..65535 }.joinToString(",")
        prefs.edit().putString(KEY_TARGET_PORTS, if (str.isNotBlank()) str else "22").apply()
        AppLogger.d(TAG, "Target ports set to: $str")
    }

    fun getBannerRegex(): String {
        return prefs.getString(KEY_BANNER_REGEX, ".*dropbear.*") ?: ".*dropbear.*"
    }

    fun setBannerRegex(regex: String) {
        val trimmed = regex.trim()
        prefs.edit().putString(KEY_BANNER_REGEX, if (trimmed.isNotBlank()) trimmed else ".*dropbear.*").apply()
        AppLogger.d(TAG, "SSH banner regex set to: $trimmed")
    }

    // --- Biometric Security Settings ---

    fun isBiometricUnlockRequired(): Boolean {
        return prefs.getBoolean(KEY_BIOMETRIC_UNLOCK, false)
    }

    fun setBiometricUnlockRequired(required: Boolean) {
        prefs.edit().putBoolean(KEY_BIOMETRIC_UNLOCK, required).apply()
        AppLogger.i(TAG, "Biometric unlock required set to: $required")
    }

    fun isAppLockEnabled(): Boolean {
        return prefs.getBoolean(KEY_APP_LOCK, false)
    }

    fun setAppLockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_APP_LOCK, enabled).apply()
        AppLogger.i(TAG, "App lock enabled set to: $enabled")
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
