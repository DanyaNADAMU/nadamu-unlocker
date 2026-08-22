package mu.nada.unlocker.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

sealed class UnlockResult {
    data class Success(val message: String) : UnlockResult()
    data class Failure(val error: String) : UnlockResult()
}

class SshUnlocker {

    /**
     * Connect to Dropbear initramfs via SSH and inject password into passfifo.
     */
    suspend fun unlock(
        host: String,
        port: Int = 22,
        password: String,
        privateKeyB64: String? = null
    ): UnlockResult = withContext(Dispatchers.IO) {
        val ssh = SSHClient()
        ssh.addHostKeyVerifier(PromiscuousVerifier())
        ssh.connectTimeout = 3000
        ssh.timeout = 5000

        try {
            ssh.connect(host, port)
            
            // Authenticate as root (Dropbear initramfs runs as root)
            ssh.authPassword("root", "root") // Fallback or key auth

            val session = ssh.startSession()
            try {
                // Command to inject password into /lib/cryptsetup/passfifo
                val sanitizedPass = password.replace("'", "'\\''")
                val cmd = "sh -c 'for f in /lib/cryptsetup/passfifo /run/cryptsetup/passfifo; do [ -p \"\$f\" ] && printf \"%s\" \"$sanitizedPass\" > \"\$f\" && echo \"OK\" && exit 0; done; exit 1'"
                
                val execution = session.exec(cmd)
                val output = execution.inputStream.bufferedReader().readText()
                execution.join(5, TimeUnit.SECONDS)

                val exitStatus = execution.exitStatus
                if (exitStatus == 0 || output.contains("OK")) {
                    UnlockResult.Success("LUKS unlocked successfully on $host:$port")
                } else {
                    UnlockResult.Failure("Passfifo write returned code $exitStatus: $output")
                }
            } finally {
                session.close()
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
