package mu.nada.unlocker.data

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.security.Security
import java.util.Base64
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory
import org.bouncycastle.jce.provider.BouncyCastleProvider

class KeyManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "nadamu_keys"
        private const val KEY_PRIVATE_PEM = "private_key_pem"
        private const val KEY_PUBLIC_OPENSSH = "public_key_openssh"
        private const val KEY_SAVED_PASS = "saved_luks_pass"
        private const val DEFAULT_COMMENT = "nadamu-android-client"

        init {
            initBouncyCastle()
        }

        /**
         * Replace Android's stripped-down BC provider with the full BouncyCastle provider
         * so that modern crypto algorithms like X25519 and Ed25519 work seamlessly.
         */
        fun initBouncyCastle() {
            try {
                Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
                Security.insertProviderAt(BouncyCastleProvider(), 1)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

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

            val b64 = Base64.getEncoder().encodeToString(baos.toByteArray())
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
            val privB64 = Base64.getEncoder().encodeToString(pkcs8Bytes)
            val privPem = "-----BEGIN PRIVATE KEY-----\n$privB64\n-----END PRIVATE KEY-----\n"

            return Pair(privPem, openSshPub)
        }

        /**
         * Derive OpenSSH public key string from any standard private key format supported
         * (PKCS#8 PEM, OpenSSL PEM, OpenSSH, RSA, Ed25519, ECDSA).
         */
        fun deriveOpenSshPublicKey(privateKeyRaw: String, comment: String = DEFAULT_COMMENT): String {
            initBouncyCastle()
            val trimmed = privateKeyRaw.trim()

            // 1. Try BouncyCastle PEMParser for standard PKCS#8 and OpenSSL PEM keys
            try {
                java.io.StringReader(trimmed).use { reader ->
                    val pemParser = org.bouncycastle.openssl.PEMParser(reader)
                    val parsedObj = pemParser.readObject()
                    if (parsedObj is PrivateKeyInfo) {
                        val privParams = org.bouncycastle.crypto.util.PrivateKeyFactory.createKey(parsedObj)
                        if (privParams is Ed25519PrivateKeyParameters) {
                            val pubParams = privParams.generatePublicKey()
                            return encodeEd25519PublicKeyToOpenSsh(pubParams.encoded, comment)
                        }
                    } else if (parsedObj is org.bouncycastle.openssl.PEMKeyPair) {
                        val converter = org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME)
                        val jcaPair = converter.getKeyPair(parsedObj)
                        val pubKey = jcaPair.public
                        val buffer = Buffer.PlainBuffer().putPublicKey(pubKey)
                        val b64 = Base64.getEncoder().encodeToString(buffer.compactData)
                        return "${KeyType.fromKey(pubKey)} $b64 $comment"
                    }
                }
            } catch (_: Exception) {}

            // 2. Fallback to SSHJ loader for OpenSSH wire/PuTTY formats
            val ssh = SSHClient()
            val kp = ssh.loadKeys(trimmed, null, null)
            val pub = kp.public
            val keyType = KeyType.fromKey(pub).toString()
            val buffer = Buffer.PlainBuffer().putPublicKey(pub)
            val b64 = Base64.getEncoder().encodeToString(buffer.compactData)
            return "$keyType $b64 $comment"
        }
    }

    private val prefs by lazy {
        try {
            val masterKey = androidx.security.crypto.MasterKey.Builder(context)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()

            androidx.security.crypto.EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
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

    /**
     * Import a custom private key (PEM / OpenSSH / RSA / Ed25519 / ECDSA).
     * Derives and stores both the private key and corresponding OpenSSH public key.
     */
    fun importPrivateKey(rawKey: String, comment: String = DEFAULT_COMMENT): Result<Pair<String, String>> {
        return try {
            val trimmed = rawKey.trim()
            if (trimmed.isEmpty()) {
                return Result.failure(IllegalArgumentException("Private key content cannot be empty"))
            }
            val openSshPub = deriveOpenSshPublicKey(trimmed, comment)
            prefs.edit()
                .putString(KEY_PRIVATE_PEM, trimmed)
                .putString(KEY_PUBLIC_OPENSSH, openSshPub)
                .apply()
            Result.success(Pair(trimmed, openSshPub))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Regenerate a brand new Ed25519 keypair and overwrite current keys.
     */
    fun regenerateKeyPair(comment: String = DEFAULT_COMMENT): Pair<String, String> {
        val (newPrivPem, newOpenSshPub) = generateEd25519KeyPair(comment)
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
