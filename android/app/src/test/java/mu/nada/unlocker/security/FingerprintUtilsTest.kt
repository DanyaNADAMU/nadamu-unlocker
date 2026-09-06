package mu.nada.unlocker.security

import mu.nada.unlocker.data.KeyManager
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Security

class FingerprintUtilsTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setupBc() {
            KeyManager.initBouncyCastle()
        }
    }

    @Test
    fun testSha256FingerprintFormat() {
        val kpg = KeyPairGenerator.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)
        val keyPair = kpg.generateKeyPair()

        val fp = FingerprintUtils.getSha256Fingerprint(keyPair.public)
        assertTrue("Fingerprint should start with SHA256: but got $fp", fp.startsWith("SHA256:"))
        // Base64 of 32 bytes SHA256 without padding is 43 characters, so "SHA256:..." is 50 chars
        assertEquals(50, fp.length)
    }

    @Test
    fun testMd5FingerprintFormat() {
        val kpg = KeyPairGenerator.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)
        val keyPair = kpg.generateKeyPair()

        val md5 = FingerprintUtils.getMd5Fingerprint(keyPair.public)
        assertTrue("MD5 fingerprint should start with MD5: but got $md5", md5.startsWith("MD5:"))
    }

    @Test
    fun testConsistentFingerprint() {
        val kpg = KeyPairGenerator.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)
        val keyPair = kpg.generateKeyPair()

        val fp1 = FingerprintUtils.getSha256Fingerprint(keyPair.public)
        val fp2 = FingerprintUtils.getSha256Fingerprint(keyPair.public)

        assertEquals(fp1, fp2)
    }
}
