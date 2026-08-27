package mu.nada.unlocker.ssh

import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

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
        val successMsg = (result as UnlockResult.Success).message
        assertTrue(successMsg.contains("LUKS unlocked"))
        assertTrue(successMsg.contains("/dev/mapper/test_crypt"))
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
    fun testUnlock_mapperPollTimesOutOnWrongPassword() = runTest {
        val mockSsh = mock(SSHClient::class.java)
        val mockKeyProvider = mock(KeyProvider::class.java)
        val mockSession1 = mock(Session::class.java)
        val mockSession2 = mock(Session::class.java)
        val mockCommand1 = mock(Session.Command::class.java)
        val mockCommand2 = mock(Session.Command::class.java)

        `when`(mockSsh.loadKeys(anyString(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull()))
            .thenReturn(mockKeyProvider)
        `when`(mockSsh.startSession()).thenReturn(mockSession1, mockSession2)

        // Fifo write ok
        `when`(mockSession1.exec(anyString())).thenReturn(mockCommand1)
        `when`(mockCommand1.inputStream).thenReturn(ByteArrayInputStream("FIFO_OK\n".toByteArray()))
        `when`(mockCommand1.exitStatus).thenReturn(0)

        // Mapper poll returns failure
        `when`(mockSession2.exec(anyString())).thenReturn(mockCommand2)
        `when`(mockCommand2.inputStream).thenReturn(ByteArrayInputStream("".toByteArray()))
        `when`(mockCommand2.exitStatus).thenReturn(1)

        val unlocker = SshUnlocker(clientFactory = { mockSsh })
        val result = unlocker.unlock(
            host = "192.168.42.10",
            port = 22,
            password = "wrong-password",
            privateKeyPem = samplePrivateKeyPem,
            mapperTarget = "test_crypt",
            pollTimeoutSeconds = 1
        )

        assertTrue("Expected Failure result, got $result", result is UnlockResult.Failure)
        val errorMsg = (result as UnlockResult.Failure).error
        assertTrue(errorMsg.contains("Unlock verification timed out"))
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
