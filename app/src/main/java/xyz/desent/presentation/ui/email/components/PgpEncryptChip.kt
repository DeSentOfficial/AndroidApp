package xyz.desent.presentation.ui.email.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.desent.presentation.ui.components.EditorBarIcon
import xyz.desent.presentation.ui.email.viewmodel.PgpComposeLock

/**
 * The compose/reply lock toggle (ANDROID_PGP.md §4.2). Selected = the send
 * will be PGP-encrypted. Disabled while the recipient's key is undiscovered;
 * when the account holds no key at all the chip instead surfaces the
 * generate/import nudge via [onNeedKey].
 */
@Composable
fun PgpEncryptChip(
    state: PgpComposeLock.State,
    enabled: Boolean,
    onToggle: () -> Unit,
    onNeedKey: () -> Unit
) {
    FilterChip(
        selected = state.locked,
        onClick = { if (state.hasKey) onToggle() else onNeedKey() },
        enabled = enabled && (state.canLock || !state.hasKey),
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (state.locked) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(if (state.locked) " Encrypted" else " Encrypt")
            }
        }
    )
}

/**
 * Icon-only lock for the redesigned bottom bars: a closed lock tinted with
 * the accent (plus a status dot) when the send will be PGP-encrypted, an
 * open muted lock otherwise. Same contract as [PgpEncryptChip] — tapping
 * toggles; without a key on the account it routes to PGP settings.
 */
@Composable
fun PgpLockButton(
    state: PgpComposeLock.State,
    enabled: Boolean,
    onToggle: () -> Unit,
    onNeedKey: () -> Unit
) {
    EditorBarIcon(
        onClick = { if (state.hasKey) onToggle() else onNeedKey() },
        enabled = enabled && (state.canLock || !state.hasKey),
        active = state.locked
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (state.locked) Icons.Default.Lock else Icons.Default.LockOpen,
                contentDescription = if (state.locked) "Encrypted" else "Encrypt",
                modifier = Modifier.size(22.dp)
            )
            if (state.locked) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(6.dp)
                        .background(MaterialTheme.colorScheme.secondary, CircleShape)
                )
            }
        }
    }
}
