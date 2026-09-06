package mu.nada.unlocker.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import mu.nada.unlocker.log.AppLogger
import mu.nada.unlocker.security.HostKeyManager
import mu.nada.unlocker.security.TofuHostKeyVerifier
import mu.nada.unlocker.security.TofuPromptCallback
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.util.concurrent.TimeUnit

sealed class UnlockResult {
    data class Success(val message: String, val mapperName: String = "auto") : UnlockResult()
    data class Failure(val error: String) : UnlockResult()
}

class SshUnlocker(
    private val clientFactory: () -> SSHClient = { SSHClient() }
) {

    companion object {
        private const val TAG = "SshUnlocker"

        init {
            mu.nada.unlocker.data.KeyManager.initBouncyCastle()
        }
    }

    /**
     * Connect to Dropbear initramfs via SSH, authenticate using client's private key,
     * inject password into passfifo, and adaptively poll mapper devices to confirm successful LUKS opening.
     */
    suspend fun unlock(
        host: String,
        port: Int = 22,
        password: String,
        privateKeyPem: String,
        mapperTarget: String? = null,
        pollTimeoutSeconds: Int = 15,
        hostKeyVerifier: HostKeyVerifier? = null,
        hostKeyManager: HostKeyManager? = null,
        tofuPrompt: TofuPromptCallback? = null
    ): UnlockResult = withContext(Dispatchers.IO) {
        val ssh = clientFactory()

        val verifier = when {
            hostKeyVerifier != null -> hostKeyVerifier
            hostKeyManager != null -> TofuHostKeyVerifier(hostKeyManager, tofuPrompt)
            else -> PromiscuousVerifier()
        }

        ssh.addHostKeyVerifier(verifier)
        ssh.connectTimeout = 7000
        ssh.timeout = 7000

        try {
            AppLogger.i(TAG, "Connecting to SSH server at $host:$port...")
            ssh.connect(host, port)

            AppLogger.i(TAG, "Authenticating with public key...")
            val keyProvider = ssh.loadKeys(privateKeyPem, null, null)
            ssh.authPublickey("root", keyProvider)
            AppLogger.i(TAG, "SSH Public key authentication successful")

            // Step 1: Write passphrase into first existing passfifo
            val sanitizedPass = password.replace("'", "'\\''")
            val fifoCmd = "sh -c 'for f in /lib/cryptsetup/passfifo /run/cryptsetup/passfifo; do [ -p \"\$f\" ] && printf \"%s\" \"$sanitizedPass\" > \"\$f\" && echo \"FIFO_OK\" && exit 0; done; exit 1'"

            AppLogger.d(TAG, "Writing passphrase to passfifo...")
            val writeSession = ssh.startSession()
            val fifoOutput: String
            val writeExitStatus: Int?
            try {
                val execution = writeSession.exec(fifoCmd)
                fifoOutput = execution.inputStream.bufferedReader().readText()
                execution.join(5, TimeUnit.SECONDS)
                writeExitStatus = execution.exitStatus
            } finally {
                try {
                    writeSession.close()
                } catch (_: Exception) {}
            }

            val fifoSuccess = (writeExitStatus == 0 || fifoOutput.contains("FIFO_OK"))
            if (!fifoSuccess) {
                val errorMsg = "Passfifo write failed (code $writeExitStatus): $fifoOutput"
                AppLogger.e(TAG, errorMsg)
                return@withContext UnlockResult.Failure(errorMsg)
            }

            AppLogger.i(TAG, "Passphrase successfully injected into passfifo. Verifying mapper opening...")

            // Step 2: Adaptive mapper polling
            val checkMapperCmd = if (!mapperTarget.isNullOrBlank() && mapperTarget != "auto") {
                "sh -c '[ -b /dev/mapper/$mapperTarget ] || ls /dev/mapper/$mapperTarget'"
            } else {
                "sh -c 'MAPPERS=\$(ls /dev/mapper 2>/dev/null | grep -v \"^control\$\"); if [ -n \"\$MAPPERS\" ]; then echo \"UNLOCKED:\$MAPPERS\"; exit 0; fi; exit 1'"
            }

            val startTime = System.currentTimeMillis()
            val maxDurationMs = pollTimeoutSeconds * 1000L

            var mapperFoundName: String? = null
            var handoffDisconnect = false

            while (System.currentTimeMillis() - startTime < maxDurationMs) {
                try {
                    if (!ssh.isConnected) {
                        handoffDisconnect = true
                        break
                    }

                    val pollSession = ssh.startSession()
                    try {
                        val pollExec = pollSession.exec(checkMapperCmd)
                        val out = pollExec.inputStream.bufferedReader().readText()
                        pollExec.join(3, TimeUnit.SECONDS)

                        if (pollExec.exitStatus == 0) {
                            mapperFoundName = when {
                                out.contains("UNLOCKED:") -> out.substringAfter("UNLOCKED:").trim().lines().firstOrNull() ?: "luks"
                                !mapperTarget.isNullOrBlank() -> mapperTarget
                                out.isNotBlank() -> out.trim().lines().firstOrNull() ?: "luks"
                                else -> "luks"
                            }
                            break
                        }
                    } finally {
                        try {
                            pollSession.close()
                        } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    val msg = e.message ?: ""
                    AppLogger.d(TAG, "SSH session disconnect during mapper poll (handoff): $msg")
                    val isConnectionSevered = e is SocketException || e is EOFException || e is TransportException ||
                            msg.contains("closed", ignoreCase = true) || msg.contains("abort", ignoreCase = true) ||
                            msg.contains("reset", ignoreCase = true) || msg.contains("not connected", ignoreCase = true) ||
                            msg.contains("timeout", ignoreCase = true)

                    if (isConnectionSevered) {
                        handoffDisconnect = true
                        break
                    }
                }
                delay(1000)
            }

            if (mapperFoundName != null) {
                val successMessage = "LUKS partition unlocked (mapper: $mapperFoundName) on $host:$port"
                AppLogger.i(TAG, "[SUCCESS] $successMessage")
                UnlockResult.Success(successMessage, mapperFoundName)
            } else if (handoffDisconnect && fifoSuccess) {
                val handoffMsg = "Passphrase delivered (FIFO_OK). Initramfs closed SSH session and handed off to main rootfs."
                AppLogger.i(TAG, "[SUCCESS] $handoffMsg")
                UnlockResult.Success(handoffMsg, "rootfs")
            } else {
                val errorMsg = "Unlock verification timed out: no opened LUKS mapper device found (invalid passphrase)"
                AppLogger.w(TAG, errorMsg)
                UnlockResult.Failure(errorMsg)
            }
        } catch (e: Exception) {
            val failureMsg = if (verifier is TofuHostKeyVerifier && verifier.lastVerificationError != null) {
                verifier.lastVerificationError!!
            } else if (e is net.schmizz.sshj.userauth.UserAuthException) {
                "SSH Auth Rejected: Public key not authorized in laptop's /etc/dropbear/initramfs/authorized_keys."
            } else {
                "SSH Unlock failed: ${e.message ?: e.javaClass.simpleName}"
            }
            AppLogger.e(TAG, failureMsg, e)
            UnlockResult.Failure(failureMsg)
        } finally {
            try {
                ssh.disconnect()
            } catch (_: Exception) {}
        }
    }
}
