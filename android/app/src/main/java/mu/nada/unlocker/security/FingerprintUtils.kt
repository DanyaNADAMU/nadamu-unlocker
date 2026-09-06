package mu.nada.unlocker.security

import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

object FingerprintUtils {

    /**
     * Compute the standard OpenSSH SHA-256 fingerprint:
     * "SHA256:<Base64 without trailing padding = >"
     */
    fun getSha256Fingerprint(key: PublicKey): String {
        val wireBytes = getOpenSshWireBytes(key)
        val digest = MessageDigest.getInstance("SHA-256").digest(wireBytes)
        val b64 = Base64.getEncoder().withoutPadding().encodeToString(digest)
        return "SHA256:$b64"
    }

    /**
     * Compute the legacy OpenSSH MD5 fingerprint:
     * "MD5:xx:xx:..."
     */
    fun getMd5Fingerprint(key: PublicKey): String {
        val wireBytes = getOpenSshWireBytes(key)
        val digest = MessageDigest.getInstance("MD5").digest(wireBytes)
        val hex = digest.joinToString(":") { String.format("%02x", it) }
        return "MD5:$hex"
    }

    /**
     * Extract OpenSSH wire-encoded public key bytes for a given PublicKey.
     */
    fun getOpenSshWireBytes(key: PublicKey): ByteArray {
        val buffer = Buffer.PlainBuffer().putPublicKey(key)
        return buffer.compactData
    }

    /**
     * Get a human-readable key type name (e.g. "ssh-ed25519", "rsa-sha2-512", "ecdsa-sha2-nistp256").
     */
    fun getKeyTypeName(key: PublicKey): String {
        return try {
            KeyType.fromKey(key).toString()
        } catch (_: Exception) {
            key.algorithm
        }
    }
}
