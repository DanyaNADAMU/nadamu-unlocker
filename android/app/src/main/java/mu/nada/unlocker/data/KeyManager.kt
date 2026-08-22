package mu.nada.unlocker.data

import android.content.Context
import android.util.Base64
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec

class KeyManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("nadamu_keys", Context.MODE_PRIVATE)

    /**
     * Get or generate Ed25519/ECDSA identity key.
     */
    fun getOrGenerateKeyPair(): Pair<String, String> {
        val privKey = prefs.getString("private_key_b64", null)
        val pubKey = prefs.getString("public_key_openssh", null)

        if (privKey != null && pubKey != null) {
            return Pair(privKey, pubKey)
        }

        // Generate software Ed25519 or ECDSA P-256 for SSH compatibility
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val pair = kpg.generateKeyPair()

        val privEncoded = Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP)
        val pubEncoded = Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP)
        val openSshPub = "ecdsa-sha2-nistp256 $pubEncoded nadamu-android-client"

        prefs.edit()
            .putString("private_key_b64", privEncoded)
            .putString("public_key_openssh", openSshPub)
            .apply()

        return Pair(privEncoded, openSshPub)
    }

    fun getPublicKeyOpenSsh(): String {
        return getOrGenerateKeyPair().second
    }

    fun savePassword(password: String) {
        prefs.edit().putString("saved_luks_pass", password).apply()
    }

    fun getSavedPassword(): String? {
        return prefs.getString("saved_luks_pass", null)
    }
}
