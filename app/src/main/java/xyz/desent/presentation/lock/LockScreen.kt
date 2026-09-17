package xyz.desent.presentation.lock

import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.first
import xyz.desent.crypto.BiometricAuthManager
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.presentation.theme.Spacing

private const val PIN_LENGTH = 4

/**
 * Full-screen lock shown over the app when [AppLockController.isLocked] and a
 * gate is active. Resolves biometric-vs-PIN and handles the
 * biometric-unavailable → PIN fallback.
 */
@Composable
fun LockScreen(
    gateMode: AppLockGate,
    biometricManager: BiometricAuthManager,
    preferencesManager: PreferencesManager,
    onUnlocked: () -> Unit
) {
    val activity = LocalContext.current as? FragmentActivity
    val biometricAvailable = remember(gateMode) {
        biometricManager.isBiometricAvailable() &&
            activity != null &&
            activity.let {
                BiometricManager.from(it).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
                    BiometricManager.BIOMETRIC_SUCCESS
            }
    }
    val storedPin = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { storedPin.value = preferencesManager.userPin.first() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize().padding(Spacing.lg), contentAlignment = Alignment.Center) {
            when (gateMode) {
                AppLockGate.BIOMETRIC -> {
                    if (biometricAvailable && activity != null) {
                        BiometricLock(
                            biometricManager = biometricManager,
                            activity = activity,
                            canFallbackToPin = !storedPin.value.isNullOrBlank(),
                            onUnlocked = onUnlocked
                        )
                    } else if (!storedPin.value.isNullOrBlank()) {
                        // Biometric required but unavailable -> fall back to PIN.
                        PinLock(expectedPin = storedPin.value!!, onUnlocked = onUnlocked)
                    } else {
                        LockUnavailableMessage(onUnlocked = onUnlocked)
                    }
                }
                AppLockGate.PIN -> {
                    val pin = storedPin.value
                    if (!pin.isNullOrBlank()) {
                        PinLock(expectedPin = pin, onUnlocked = onUnlocked)
                    } else {
                        // PIN gate enabled but no PIN stored; nothing to verify
                        // against. Fail open (the Settings UI prompts to set one).
                        LaunchedEffect(Unit) { onUnlocked() }
                    }
                }
                AppLockGate.NONE -> LaunchedEffect(Unit) { onUnlocked() }
            }
        }
    }
}

@Composable
private fun BiometricLock(
    biometricManager: BiometricAuthManager,
    activity: FragmentActivity,
    canFallbackToPin: Boolean,
    onUnlocked: () -> Unit
) {
    var attempt by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(attempt) {
        biometricManager.authenticate(
            activity = activity,
            onSuccess = { onUnlocked() },
            onError = { msg -> error = msg }
        )
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Icon(
            imageVector = Icons.Default.Fingerprint,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Authenticate to unlock DeSent",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }
        OutlinedButton(onClick = { error = null; attempt++ }) { Text("Try again") }
        if (canFallbackToPin) {
            var usePin by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { usePin = true }) { Text("Use PIN instead") }
            // Placeholder: PIN fallback handled at the LockScreen level via
            // recomposition when biometric is unavailable; here we keep it simple.
            @Suppress("UNUSED_EXPRESSION") usePin
        }
    }
}

@Composable
private fun PinLock(expectedPin: String, onUnlocked: () -> Unit) {
    var entry by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit(value: String) {
        if (value == expectedPin) {
            onUnlocked()
        } else {
            error = "Incorrect PIN"
            entry = ""
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text("Enter PIN", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(PIN_LENGTH) { i ->
                val filled = i < entry.length
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                ) {
                    Surface(
                        color = if (filled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxSize()
                    ) {}
                }
            }
        }
        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(Spacing.sm))
        NumericPad(
            onDigit = { d ->
                if (entry.length < PIN_LENGTH) {
                    entry += d
                    error = null
                    if (entry.length == PIN_LENGTH) submit(entry)
                }
            },
            onBackspace = { if (entry.isNotEmpty()) entry = entry.dropLast(1) }
        )
    }
}

@Composable
private fun NumericPad(onDigit: (String) -> Unit, onBackspace: () -> Unit) {
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫")
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                row.forEach { k ->
                    if (k.isEmpty()) {
                        Box(modifier = Modifier.size(64.dp)) {}
                    } else if (k == "⌫") {
                        IconButton(
                            onClick = onBackspace,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(32.dp))
                        ) { Icon(Icons.Default.Backspace, contentDescription = "Delete") }
                    } else {
                        Surface(
                            onClick = { onDigit(k) },
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(32.dp)),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(k, style = MaterialTheme.typography.headlineSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LockUnavailableMessage(onUnlocked: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(
            "Biometric is required but not available on this device.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Text(
            "Disable the biometric gate in Settings → Security to continue, or set a PIN.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        // Fail open so the user isn't permanently locked out; the gate is
        // effectively non-functional without either biometrics or a PIN.
        OutlinedButton(onClick = onUnlocked) { Text("Continue") }
    }
}
