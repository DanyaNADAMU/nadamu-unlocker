package mu.nada.unlocker.security

import mu.nada.unlocker.log.AppLogger
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.PublicKey

enum class TofuDecision {
    TRUST_ONCE,
    TRUST_AND_PIN,
    REJECT
}

typealias TofuPromptCallback = (
    hostname: String,
    port: Int,
    fingerprint: String,
    keyType: String,
    isUntrustedMismatch: Boolean
) -> TofuDecision

class TofuHostKeyVerifier(
    private val hostKeyManager: HostKeyManager,
    private val tofuPrompt: TofuPromptCallback? = null,
    private val autoPinOnFirstUse: Boolean = false
) : HostKeyVerifier {

    companion object {
        private const val TAG = "TofuHostKeyVerifier"
    }

    var lastVerificationError: String? = null
        private set

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        lastVerificationError = null
        val fingerprint = FingerprintUtils.getSha256Fingerprint(key)
        val keyType = FingerprintUtils.getKeyTypeName(key)

        // 1. Device-Centric check: is this laptop fingerprint already trusted?
        if (hostKeyManager.isFingerprintTrusted(fingerprint)) {
            AppLogger.i(TAG, "Host key verified with trusted laptop fingerprint ($fingerprint) on $hostname:$port")
            return true
        }

        val hasExistingTrustedKeys = hostKeyManager.hasAnyTrustedKeys()

        if (hasExistingTrustedKeys) {
            val errorMsg = "UNTRUSTED HOST KEY on $hostname:$port ($fingerprint)! Does not match your trusted laptop key(s)."
            AppLogger.w(TAG, errorMsg)
        } else {
            AppLogger.i(TAG, "Initial setup: host key detected for $hostname:$port ($keyType, $fingerprint)")
        }

        if (tofuPrompt != null) {
            val decision = tofuPrompt.invoke(hostname, port, fingerprint, keyType, hasExistingTrustedKeys)
            return when (decision) {
                TofuDecision.TRUST_AND_PIN -> {
                    hostKeyManager.trustFingerprint(fingerprint, "Laptop ($hostname)")
                    AppLogger.i(TAG, "TOFU: Trusted and saved host key fingerprint ($fingerprint)")
                    true
                }
                TofuDecision.TRUST_ONCE -> {
                    AppLogger.i(TAG, "TOFU: Trusted once for $hostname:$port ($fingerprint)")
                    true
                }
                TofuDecision.REJECT -> {
                    val rejectedMsg = "Host key rejected for $hostname:$port ($fingerprint)"
                    AppLogger.w(TAG, rejectedMsg)
                    lastVerificationError = rejectedMsg
                    false
                }
            }
        }

        if (autoPinOnFirstUse && !hasExistingTrustedKeys) {
            hostKeyManager.trustFingerprint(fingerprint, "Laptop ($hostname)")
            AppLogger.i(TAG, "Auto-pinned laptop host key on first use ($fingerprint)")
            return true
        }

        val errorMsg = if (hasExistingTrustedKeys) {
            "Host key mismatch / Untrusted host on $hostname:$port ($fingerprint) - Potential MitM attack!"
        } else {
            "Untrusted host key for $hostname:$port ($fingerprint) and no TOFU prompt available"
        }
        AppLogger.w(TAG, errorMsg)
        lastVerificationError = errorMsg
        return false
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> {
        return emptyList()
    }
}
