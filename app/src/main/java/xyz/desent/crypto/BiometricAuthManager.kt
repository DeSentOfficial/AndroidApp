package xyz.desent.crypto

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

class BiometricAuthManager(
    private val context: Context
) {

    /**
     * Whether a biometric (fingerprint/face) authenticator is available and
     * enrolled on this device. Used to decide whether to prompt for biometric
     * or fall back to PIN.
     */
    fun isBiometricAvailable(): Boolean {
        val bm = BiometricManager.from(context)
        return bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    fun authenticate(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val biometricPromptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Authenticate to access Nostr Status")
            .setSubtitle("Use your biometric to decrypt your key")
            .setNegativeButtonText("Cancel")
            .setConfirmationRequired(false)
            .build()
        
        val biometricPrompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }
                
                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    onError("Authentication failed")
                }
                
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    when (errorCode) {
                        BiometricPrompt.ERROR_USER_CANCELED -> onError("Authentication cancelled")
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON -> onError("Authentication cancelled")
                        BiometricPrompt.ERROR_LOCKOUT -> onError("Too many attempts, try again later")
                        else -> onError(errString.toString())
                    }
                }
            }
        )
        
        biometricPrompt.authenticate(biometricPromptInfo)
    }
}
