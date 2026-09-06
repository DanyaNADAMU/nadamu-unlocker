package mu.nada.unlocker.security

import mu.nada.unlocker.data.KeyManager
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
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
    fun testVerify_pinnedHostMatches() {
        val kpg = KeyPairGenerator.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)
        val key = kpg.generateKeyPair().public
        val expectedFp = FingerprintUtils.getSha256Fingerprint(key)

        `when`(hostKeyManager.getPinnedFingerprint("192.168.43.1", 22)).thenReturn(expectedFp)

        val verifier = TofuHostKeyVerifier(hostKeyManager)
        val verified = verifier.verify("192.168.43.1", 22, key)

        assertTrue("Expected pinned key to verify successfully", verified)
    }

    @Test
    fun testVerify_pinnedHostMismatchRejectsAndReportsMitm() {
        val kpg = KeyPairGenerator.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)
        val key = kpg.generateKeyPair().public
        val differentKey = kpg.generateKeyPair().public
        val originalFp = FingerprintUtils.getSha256Fingerprint(key)

        `when`(hostKeyManager.getPinnedFingerprint("192.168.43.1", 22)).thenReturn(originalFp)

        val verifier = TofuHostKeyVerifier(hostKeyManager)
        val verified = verifier.verify("192.168.43.1", 22, differentKey)

        assertFalse("Expected host key mismatch to fail verification", verified)
        assertTrue(verifier.lastVerificationError?.contains("HOST KEY MISMATCH") == true)
        assertTrue(verifier.lastVerificationError?.contains("MitM attack") == true)
    }

    @Test
    fun testVerify_tofuTrustOnce() {
        val kpg = KeyPairGenerator.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)
        val key = kpg.generateKeyPair().public

        `when`(hostKeyManager.getPinnedFingerprint("192.168.43.1", 22)).thenReturn(null)

        val verifier = TofuHostKeyVerifier(
            hostKeyManager = hostKeyManager,
            tofuPrompt = { _, _, _, _ -> TofuDecision.TRUST_ONCE }
        )

        val verified = verifier.verify("192.168.43.1", 22, key)
        assertTrue(verified)
    }

    @Test
    fun testVerify_tofuReject() {
        val kpg = KeyPairGenerator.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)
        val key = kpg.generateKeyPair().public

        `when`(hostKeyManager.getPinnedFingerprint("192.168.43.1", 22)).thenReturn(null)

        val verifier = TofuHostKeyVerifier(
            hostKeyManager = hostKeyManager,
            tofuPrompt = { _, _, _, _ -> TofuDecision.REJECT }
        )

        val verified = verifier.verify("192.168.43.1", 22, key)
        assertFalse(verified)
        assertTrue(verifier.lastVerificationError?.contains("rejected") == true)
    }
}
