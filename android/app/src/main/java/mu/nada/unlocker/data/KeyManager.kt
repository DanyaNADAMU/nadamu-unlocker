package mu.nada.unlocker.data

import android.content.Context
import android.util.Base64
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.SecureRandom

class KeyManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "nadamu_keys"
        private const val KEY_PRIVATE_PEM = "private_key_pem"
        private const val KEY_PUBLIC_OPENSSH = "public_key_openssh"
        private const val KEY_SAVED_PASS = "saved_luks_pass"
        private const val DEFAULT_COMMENT = "nadamu-android-client"

        /**
         * Pure helper to encode Ed25519 raw public key bytes into OpenSSH wire format.
         * Format: "ssh-ed25519 <base64( [uint32 len(11)][ssh-ed25519][uint32 len(32)][pub_bytes] )> <comment>"
         */
        fun encodeEd25519PublicKeyToOpenSsh(
            pubBytes: ByteArray,
            comment: String = DEFAULT_COMMENT
        ): String {
            val baos = ByteArrayOutputStream()
            val dos = DataOutputStream(baos)

            val keyType = "ssh-ed25519".toByteArray(Charsets.US_ASCII)
            dos.writeInt(keyType.size)
            dos.write(keyType)

            dos.writeInt(pubBytes.size)
            dos.write(pubBytes)

            val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            return "ssh-ed25519 $b64 $comment"
        }

        /**
         * Pure helper to generate an Ed25519 keypair and return (privateKeyPem, openSshPubKey).
         */
        fun generateEd25519KeyPair(comment: String = DEFAULT_COMMENT): Pair<String, String> {
            val random = SecureRandom()
            val gen = Ed25519KeyPairGenerator()
            gen.init(Ed25519KeyGenerationParameters(random))
            val pair = gen.generateKeyPair()

            val pubParams = pair.public as Ed25519PublicKeyParameters
            val privParams = pair.private as Ed25519PrivateKeyParameters

            // Encode OpenSSH public key
            val openSshPub = encodeEd25519PublicKeyToOpenSsh(pubParams.encoded, comment)

            // Encode PKCS#8 PEM private key
            val pki: PrivateKeyInfo = PrivateKeyInfoFactory.createPrivateKeyInfo(privParams)
            val pkcs8Bytes = pki.encoded
            val privB64 = Base64.encodeToString(pkcs8Bytes, Base64.NO_WRAP)
            val privPem = "-----BEGIN PRIVATE KEY-----\n$privB64\n-----END PRIVATE KEY-----\n"

            return Pair(privPem, openSshPub)
        }
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Get or generate Ed25519 identity keypair.
     * Returns Pair(privateKeyPem, openSshPublicKey).
     */
    fun getOrGenerateKeyPair(): Pair<String, String> {
        val privKey = prefs.getString(KEY_PRIVATE_PEM, null)
        val pubKey = prefs.getString(KEY_PUBLIC_OPENSSH, null)

        if (privKey != null && pubKey != null) {
            return Pair(privKey, pubKey)
        }

        val (newPrivPem, newOpenSshPub) = generateEd25519KeyPair()

        prefs.edit()
            .putString(KEY_PRIVATE_PEM, newPrivPem)
            .putString(KEY_PUBLIC_OPENSSH, newOpenSshPub)
            .apply()

        return Pair(newPrivPem, newOpenSshPub)
    }

    fun getPublicKeyOpenSsh(): String {
        return getOrGenerateKeyPair().second
    }

    fun getPrivateKeyPem(): String {
        return getOrGenerateKeyPair().first
    }

    fun savePassword(password: String) {
        prefs.edit().putString(KEY_SAVED_PASS, password).apply()
    }

    fun getSavedPassword(): String? {
        return prefs.getString(KEY_SAVED_PASS, null)
    }
}
