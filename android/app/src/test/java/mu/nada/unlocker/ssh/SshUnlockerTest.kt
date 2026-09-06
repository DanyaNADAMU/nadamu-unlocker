package mu.nada.unlocker.ssh

import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.SocketException

class SshUnlockerTest {

    private val samplePrivateKeyPem = "-----BEGIN PRIVATE KEY-----\nMC4CAQAwBQYDK2VwBCIEIFakeKeyBytesForTestingOnly1234567890abcdef\n-----END PRIVATE KEY-----\n"

    @Test
    fun testUnlock_successfulEndToEnd() = runTest {
        val mockSsh = mock(SSHClient::class.java)
        val mockKeyProvider = mock(KeyProvider::class.java)
        val mockSession1 = mock(Session::class.java)
        val mockSession2 = mock(Session::class.java)
        val mockCommand1 = mock(Session.Command::class.java)
        val mockCommand2 = mock(Session.Command::class.java)

        `when`(mockSsh.loadKeys(anyString(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull()))
            .thenReturn(mockKeyProvider)
        `when`(mockSsh.startSession()).thenReturn(mockSession1, mockSession2)
        `when`(mockSsh.isConnected).thenReturn(true)

        // Session 1: Passfifo write
        `when`(mockSession1.exec(anyString())).thenReturn(mockCommand1)
        `when`(mockCommand1.inputStream).thenReturn(ByteArrayInputStream("FIFO_OK\n".toByteArray()))
        `when`(mockCommand1.exitStatus).thenReturn(0)

        // Session 2: Mapper poll
        `when`(mockSession2.exec(anyString())).thenReturn(mockCommand2)
        `when`(mockCommand2.inputStream).thenReturn(ByteArrayInputStream("test_crypt\n".toByteArray()))
        `when`(mockCommand2.exitStatus).thenReturn(0)

        val unlocker = SshUnlocker(clientFactory = { mockSsh })
        val result = unlocker.unlock(
            host = "192.168.42.10",
            port = 22,
            password = "secret-password",
            privateKeyPem = samplePrivateKeyPem,
            mapperTarget = "test_crypt",
            pollTimeoutSeconds = 2
        )

        assertTrue("Expected Success result, got $result", result is UnlockResult.Success)
        val success = result as UnlockResult.Success
        assertTrue(success.message.contains("LUKS partition unlocked"))
        assertTrue(success.message.contains("test_crypt"))
        assertEquals("test_crypt", success.mapperName)
    }

    @Test
    fun testUnlock_adaptiveMapperDiscoverySuccess() = runTest {
        val mockSsh = mock(SSHClient::class.java)
        val mockKeyProvider = mock(KeyProvider::class.java)
        val mockSession1 = mock(Session::class.java)
        val mockSession2 = mock(Session::class.java)
        val mockCommand1 = mock(Session.Command::class.java)
        val mockCommand2 = mock(Session.Command::class.java)

        `when`(mockSsh.loadKeys(anyString(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull()))
            .thenReturn(mockKeyProvider)
        `when`(mockSsh.startSession()).thenReturn(mockSession1, mockSession2)
        `when`(mockSsh.isConnected).thenReturn(true)

        // Session 1: Passfifo write
        `when`(mockSession1.exec(anyString())).thenReturn(mockCommand1)
        `when`(mockCommand1.inputStream).thenReturn(ByteArrayInputStream("FIFO_OK\n".toByteArray()))
        `when`(mockCommand1.exitStatus).thenReturn(0)

        // Session 2: Adaptive mapper poll returning custom NVMe mapper name
        `when`(mockSession2.exec(anyString())).thenReturn(mockCommand2)
        `when`(mockCommand2.inputStream).thenReturn(ByteArrayInputStream("UNLOCKED:nvme0n1p3_crypt\n".toByteArray()))
        `when`(mockCommand2.exitStatus).thenReturn(0)

        val unlocker = SshUnlocker(clientFactory = { mockSsh })
        val result = unlocker.unlock(
            host = "192.168.43.15",
            port = 22,
            password = "secret-password",
            privateKeyPem = samplePrivateKeyPem,
            mapperTarget = null,
            pollTimeoutSeconds = 2
        )

        assertTrue("Expected Success result, got $result", result is UnlockResult.Success)
        val success = result as UnlockResult.Success
        assertTrue(success.message.contains("nvme0n1p3_crypt"))
        assertEquals("nvme0n1p3_crypt", success.mapperName)
    }

    @Test
    fun testUnlock_pivotHandoffOnFifoOkWithSocketSevered() = runTest {
        val mockSsh = mock(SSHClient::class.java)
        val mockKeyProvider = mock(KeyProvider::class.java)
        val mockSession1 = mock(Session::class.java)
        val mockCommand1 = mock(Session.Command::class.java)

        `when`(mockSsh.loadKeys(anyString(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull()))
            .thenReturn(mockKeyProvider)
        `when`(mockSsh.startSession()).thenReturn(mockSession1).thenThrow(net.schmizz.sshj.transport.TransportException("Software caused connection abort"))
        `when`(mockSsh.isConnected).thenReturn(true).thenReturn(false)

        // Session 1: Passfifo write succeeds
        `when`(mockSession1.exec(anyString())).thenReturn(mockCommand1)
        `when`(mockCommand1.inputStream).thenReturn(ByteArrayInputStream("FIFO_OK\n".toByteArray()))
        `when`(mockCommand1.exitStatus).thenReturn(0)

        val unlocker = SshUnlocker(clientFactory = { mockSsh })
        val result = unlocker.unlock(
            host = "10.77.78.206",
            port = 22,
            password = "secret-password",
            privateKeyPem = samplePrivateKeyPem,
            pollTimeoutSeconds = 2
        )

        assertTrue("Expected Success on pivot_root handoff, got $result", result is UnlockResult.Success)
        val success = result as UnlockResult.Success
        assertTrue(success.message.contains("handed off"))
    }

    @Test
    fun testUnlock_fifoWriteFails() = runTest {
        val mockSsh = mock(SSHClient::class.java)
        val mockKeyProvider = mock(KeyProvider::class.java)
        val mockSession = mock(Session::class.java)
        val mockCommand = mock(Session.Command::class.java)

        `when`(mockSsh.loadKeys(anyString(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull()))
            .thenReturn(mockKeyProvider)
        `when`(mockSsh.startSession()).thenReturn(mockSession)

        `when`(mockSession.exec(anyString())).thenReturn(mockCommand)
        `when`(mockCommand.inputStream).thenReturn(ByteArrayInputStream("No fifo found\n".toByteArray()))
        `when`(mockCommand.exitStatus).thenReturn(1)

        val unlocker = SshUnlocker(clientFactory = { mockSsh })
        val result = unlocker.unlock(
            host = "192.168.42.10",
            port = 22,
            password = "secret-password",
            privateKeyPem = samplePrivateKeyPem,
            mapperTarget = "test_crypt",
            pollTimeoutSeconds = 2
        )

        assertTrue("Expected Failure result, got $result", result is UnlockResult.Failure)
        val errorMsg = (result as UnlockResult.Failure).error
        assertTrue(errorMsg.contains("Passfifo write failed"))
    }

    @Test
    fun testUnlock_connectionErrorHandledGracefully() = runTest {
        val mockSsh = mock(SSHClient::class.java)
        `when`(mockSsh.connect(anyString(), anyInt())).thenThrow(IOException("Connection refused"))

        val unlocker = SshUnlocker(clientFactory = { mockSsh })
        val result = unlocker.unlock(
            host = "192.168.42.10",
            port = 22,
            password = "password",
            privateKeyPem = samplePrivateKeyPem
        )

        assertTrue(result is UnlockResult.Failure)
        assertTrue((result as UnlockResult.Failure).error.contains("Connection refused"))
    }
}
