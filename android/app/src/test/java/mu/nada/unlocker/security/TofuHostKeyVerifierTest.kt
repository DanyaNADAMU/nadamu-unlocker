package mu.nada.unlocker.security

import mu.nada.unlocker.data.KeyManager
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.security.KeyPairGenerator

class TofuHostKeyVerifierTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setupBc() {
            KeyManager.initBouncyCastle()
        }
    }

    private lateinit var hostKeyManager: HostKeyManager

    @Before
    fun setup() {
        hostKeyManager = mock(HostKeyManager::class.java)
    }

    @Test
    fun testVerify_deviceCentricMatchesRegardlessOfIp() {
        val kpg = KeyPairGenerator.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)
        val key = kpg.generateKeyPair().public
        val expectedFp = FingerprintUtils.getSha256Fingerprint(key)

        `when`(hostKeyManager.isFingerprintTrusted(expectedFp)).thenReturn(true)

        val verifier = TofuHostKeyVerifier(hostKeyManager)

        // Same laptop key must be accepted across USB, Hotspot, and Wi-Fi IPs without prompting!
        assertTrue(verifier.verify("10.77.78.206", 22, key))
        assertTrue(verifier.verify("10.137.172.122", 22, key))
        assertTrue(verifier.verify("10.193.60.143", 22, key))
    }

    @Test
    fun testVerify_untrustedMismatchTriggersMitmWarning() {
        val kpg = KeyPairGenerator.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)
        val trustedKey = kpg.generateKeyPair().public
        val rogueKey = kpg.generateKeyPair().public
        val rogueFp = FingerprintUtils.getSha256Fingerprint(rogueKey)

        `when`(hostKeyManager.isFingerprintTrusted(rogueFp)).thenReturn(false)
        `when`(hostKeyManager.hasAnyTrustedKeys()).thenReturn(true)

        var reportedMismatch = false
        val verifier = TofuHostKeyVerifier(
            hostKeyManager = hostKeyManager,
            tofuPrompt = { _, _, _, _, isMismatch ->
                reportedMismatch = isMismatch
                TofuDecision.REJECT
            }
        )

        val verified = verifier.verify("10.193.60.100", 22, rogueKey)
        assertFalse(verified)
        assertTrue(reportedMismatch)
        assertTrue(verifier.lastVerificationError?.contains("rejected") == true)
    }

    @Test
    fun testVerify_tofuTrustOnce() {
        val kpg = KeyPairGenerator.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)
        val key = kpg.generateKeyPair().public
        val fp = FingerprintUtils.getSha256Fingerprint(key)

        `when`(hostKeyManager.isFingerprintTrusted(fp)).thenReturn(false)
        `when`(hostKeyManager.hasAnyTrustedKeys()).thenReturn(false)

        val verifier = TofuHostKeyVerifier(
            hostKeyManager = hostKeyManager,
            tofuPrompt = { _, _, _, _, _ -> TofuDecision.TRUST_ONCE }
        )

        val verified = verifier.verify("192.168.43.1", 22, key)
        assertTrue(verified)
    }

    @Test
    fun testVerify_tofuReject() {
        val kpg = KeyPairGenerator.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)
        val key = kpg.generateKeyPair().public
        val fp = FingerprintUtils.getSha256Fingerprint(key)

        `when`(hostKeyManager.isFingerprintTrusted(fp)).thenReturn(false)
        `when`(hostKeyManager.hasAnyTrustedKeys()).thenReturn(false)

        val verifier = TofuHostKeyVerifier(
            hostKeyManager = hostKeyManager,
            tofuPrompt = { _, _, _, _, _ -> TofuDecision.REJECT }
        )

        val verified = verifier.verify("192.168.43.1", 22, key)
        assertFalse(verified)
        assertTrue(verifier.lastVerificationError?.contains("rejected") == true)
    }
}
