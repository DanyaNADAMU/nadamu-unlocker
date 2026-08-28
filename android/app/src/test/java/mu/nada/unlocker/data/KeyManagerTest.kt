package mu.nada.unlocker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class KeyManagerTest {

    @Test
    fun testGenerateEd25519KeyPair_producesValidOpenSshAndPem() {
        val (privPem, openSshPub) = KeyManager.generateEd25519KeyPair("test-client")

        // 1. Verify PEM private key format
        assertTrue(privPem.startsWith("-----BEGIN PRIVATE KEY-----\n"))
        assertTrue(privPem.endsWith("-----END PRIVATE KEY-----\n"))

        // 2. Verify OpenSSH public key format
        val parts = openSshPub.split(" ")
        assertEquals(3, parts.size)
        assertEquals("ssh-ed25519", parts[0])
        assertEquals("test-client", parts[2])

        // 3. Decode OpenSSH base64 blob and verify wire format structure
        val wireBytes = Base64.getDecoder().decode(parts[1])
        // Wire format: 4-byte len (11) + "ssh-ed25519" (11 bytes) + 4-byte len (32) + 32-byte pubkey = 51 bytes
        assertEquals(51, wireBytes.size)

        val keyTypeLen = ((wireBytes[0].toInt() and 0xFF) shl 24) or
                ((wireBytes[1].toInt() and 0xFF) shl 16) or
                ((wireBytes[2].toInt() and 0xFF) shl 8) or
                (wireBytes[3].toInt() and 0xFF)
        assertEquals(11, keyTypeLen)

        val keyType = String(wireBytes, 4, 11, Charsets.US_ASCII)
        assertEquals("ssh-ed25519", keyType)

        val pubKeyLen = ((wireBytes[15].toInt() and 0xFF) shl 24) or
                ((wireBytes[16].toInt() and 0xFF) shl 16) or
                ((wireBytes[17].toInt() and 0xFF) shl 8) or
                (wireBytes[18].toInt() and 0xFF)
        assertEquals(32, pubKeyLen)
    }

    @Test
    fun testEncodeEd25519PublicKeyToOpenSsh_exactBytes() {
        val dummyPub = ByteArray(32) { it.toByte() }
        val pubStr = KeyManager.encodeEd25519PublicKeyToOpenSsh(dummyPub, "comment-test")

        val parts = pubStr.split(" ")
        assertEquals("ssh-ed25519", parts[0])
        assertEquals("comment-test", parts[2])

        val decoded = Base64.getDecoder().decode(parts[1])
        assertEquals(51, decoded.size)
        val extractedPub = decoded.copyOfRange(19, 51)
        for (i in 0 until 32) {
            assertEquals(dummyPub[i], extractedPub[i])
        }
    }

    @Test
    fun testDeriveOpenSshPublicKey_fromGeneratedKey() {
        val (privPem, openSshPub) = KeyManager.generateEd25519KeyPair("custom-user")
        val derivedPub = KeyManager.deriveOpenSshPublicKey(privPem, "custom-user")

        val expectedParts = openSshPub.split(" ")
        val derivedParts = derivedPub.split(" ")

        assertEquals(expectedParts[0], derivedParts[0]) // ssh-ed25519
        assertEquals(expectedParts[1], derivedParts[1]) // key bytes base64
        assertEquals("custom-user", derivedParts[2])
    }
}
