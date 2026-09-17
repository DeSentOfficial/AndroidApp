package xyz.desent.presentation.composition

import androidx.compose.runtime.compositionLocalOf
import xyz.desent.crypto.BiometricAuthManager

val LocalBiometricAuthManager = compositionLocalOf<BiometricAuthManager> {
    error("No BiometricAuthManager provided")
}
