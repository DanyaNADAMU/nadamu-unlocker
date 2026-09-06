package mu.nada.unlocker.security

import mu.nada.unlocker.log.AppLogger
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.PublicKey

enum class TofuDecision {
    TRUST_ONCE,
    TRUST_AND_PIN,
    REJECT
}

typealias TofuPromptCallback = (hostname: String, port: Int, fingerprint: String, keyType: String) -> TofuDecision

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
        val pinnedFingerprint = hostKeyManager.getPinnedFingerprint(hostname, port)

        if (pinnedFingerprint != null) {
            if (pinnedFingerprint == fingerprint) {
                AppLogger.i(TAG, "Host key verified successfully for $hostname:$port ($fingerprint)")
                return true
            } else {
                val errorMsg = "HOST KEY MISMATCH on $hostname:$port! Expected: $pinnedFingerprint, Got: $fingerprint. Potential MitM attack!"
                AppLogger.e(TAG, errorMsg)
                lastVerificationError = errorMsg
                return false
            }
        }

        // Host not yet pinned - TOFU flow
        AppLogger.i(TAG, "Unknown host key detected for $hostname:$port ($keyType, $fingerprint)")

        if (tofuPrompt != null) {
            val decision = tofuPrompt.invoke(hostname, port, fingerprint, keyType)
            return when (decision) {
                TofuDecision.TRUST_AND_PIN -> {
                    hostKeyManager.pinHostKey(hostname, port, fingerprint)
                    AppLogger.i(TAG, "TOFU: Trusted and pinned host key for $hostname:$port")
                    true
                }
                TofuDecision.TRUST_ONCE -> {
                    AppLogger.i(TAG, "TOFU: Trusted once for $hostname:$port")
                    true
                }
                TofuDecision.REJECT -> {
                    val errorMsg = "Host key rejected for $hostname:$port ($fingerprint)"
                    AppLogger.w(TAG, errorMsg)
                    lastVerificationError = errorMsg
                    false
                }
            }
        }

        if (autoPinOnFirstUse) {
            hostKeyManager.pinHostKey(hostname, port, fingerprint)
            AppLogger.i(TAG, "Auto-pinned host key on first use for $hostname:$port")
            return true
        }

        val errorMsg = "Untrusted host key for $hostname:$port ($fingerprint) and no TOFU prompt provided"
        AppLogger.w(TAG, errorMsg)
        lastVerificationError = errorMsg
        return false
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> {
        return emptyList()
    }
}
