package mu.nada.unlocker.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.util.concurrent.TimeUnit

sealed class UnlockResult {
    data class Success(val message: String) : UnlockResult()
    data class Failure(val error: String) : UnlockResult()
}

class SshUnlocker(
    private val clientFactory: () -> SSHClient = { SSHClient() }
) {

    companion object {
        init {
            mu.nada.unlocker.data.KeyManager.initBouncyCastle()
        }
    }

    /**
     * Connect to Dropbear initramfs via SSH, authenticate using the client's Ed25519/ECDSA private key,
     * inject password into passfifo, and poll the mapper device to confirm successful LUKS opening.
     */
    suspend fun unlock(
        host: String,
        port: Int = 22,
        password: String,
        privateKeyPem: String,
        mapperTarget: String = "test_crypt",
        pollTimeoutSeconds: Int = 15
    ): UnlockResult = withContext(Dispatchers.IO) {
        val ssh = clientFactory()
        ssh.addHostKeyVerifier(PromiscuousVerifier())
        ssh.connectTimeout = 3000
        ssh.timeout = 5000

        try {
            ssh.connect(host, port)

            val keyProvider = ssh.loadKeys(privateKeyPem, null, null)
            ssh.authPublickey("root", keyProvider)

            // Step 1: Write passphrase into first existing passfifo
            val sanitizedPass = password.replace("'", "'\\''")
            val fifoCmd = "sh -c 'for f in /lib/cryptsetup/passfifo /run/cryptsetup/passfifo; do [ -p \"\$f\" ] && printf \"%s\" \"$sanitizedPass\" > \"\$f\" && echo \"FIFO_OK\" && exit 0; done; exit 1'"

            val writeSession = ssh.startSession()
            val fifoOutput: String
            val writeExitStatus: Int?
            try {
                val execution = writeSession.exec(fifoCmd)
                fifoOutput = execution.inputStream.bufferedReader().readText()
                execution.join(5, TimeUnit.SECONDS)
                writeExitStatus = execution.exitStatus
            } finally {
                writeSession.close()
            }

            if (writeExitStatus != null && writeExitStatus != 0 && !fifoOutput.contains("FIFO_OK")) {
                return@withContext UnlockResult.Failure("Passfifo write failed (code $writeExitStatus): $fifoOutput")
            }

            // Step 2: Poll for opened mapper device according to docs/unlock-flow.md contract
            val checkMapperCmd = "sh -c '[ -b /dev/mapper/$mapperTarget ] || ls /dev/mapper/$mapperTarget'"
            val startTime = System.currentTimeMillis()
            val maxDurationMs = pollTimeoutSeconds * 1000L

            var mapperFound = false
            while (System.currentTimeMillis() - startTime < maxDurationMs) {
                try {
                    val pollSession = ssh.startSession()
                    try {
                        val pollExec = pollSession.exec(checkMapperCmd)
                        val out = pollExec.inputStream.bufferedReader().readText()
                        pollExec.join(3, TimeUnit.SECONDS)
                        if (pollExec.exitStatus == 0 || out.contains(mapperTarget)) {
                            mapperFound = true
                            break
                        }
                    } finally {
                        pollSession.close()
                    }
                } catch (e: Exception) {
                    // Drop during polling can happen if handoff / pivot occurs
                    if (mapperFound) {
                        break
                    }
                }
                delay(1000)
            }

            if (mapperFound) {
                UnlockResult.Success("LUKS unlocked and /dev/mapper/$mapperTarget verified on $host:$port")
            } else {
                UnlockResult.Failure("Unlock verification timed out: /dev/mapper/$mapperTarget not found (invalid passphrase)")
            }
        } catch (e: Exception) {
            UnlockResult.Failure("SSH Unlock failed: ${e.message}")
        } finally {
            try {
                ssh.disconnect()
            } catch (_: Exception) {}
        }
    }
}
