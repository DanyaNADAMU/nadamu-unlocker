package mu.nada.unlocker.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import mu.nada.unlocker.log.AppLogger

object BiometricHelper {

    private const val TAG = "BiometricHelper"

    fun isBiometricAvailable(context: Context): Boolean {
        val biometricManager = BiometricManager.from(context)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val result = biometricManager.canAuthenticate(authenticators)
        return result == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun authenticate(
        activity: FragmentActivity,
        title: String = "Unlock Laptop",
        subtitle: String = "Authenticate to proceed",
        onSuccess: () -> Unit,
        onError: (String) -> Unit = {}
    ) {
        if (!isBiometricAvailable(activity)) {
            AppLogger.d(TAG, "Biometrics not available; bypassing prompt")
            onSuccess()
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        val biometricPrompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    AppLogger.i(TAG, "Biometric authentication succeeded")
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    val msg = errString.toString()
                    AppLogger.w(TAG, "Biometric authentication error ($errorCode): $msg")
                    onError(msg)
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    AppLogger.w(TAG, "Biometric authentication failed (fingerprint not recognized)")
                }
            }
        )

        biometricPrompt.authenticate(promptInfo)
    }
}
