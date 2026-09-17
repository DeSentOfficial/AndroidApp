package xyz.desent.presentation.ui.email.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.desent.presentation.ui.components.DesentTextField

/**
 * "Forward to Nostr user" dialog (NIP-EMAIL forwarding): collects the target
 * npub for re-delivering stored kind-1010 mail to another key. Shared by the
 * single-message forward (detail screen) and reusable elsewhere.
 */
@Composable
fun ForwardToNpubDialog(
    isSending: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (npub: String) -> Unit
) {
    var npub by remember { mutableStateOf("") }
    val isValid = npub.trim().startsWith("npub1") && npub.trim().length > 20

    AlertDialog(
        onDismissRequest = { if (!isSending) onDismiss() },
        title = { Text("Forward to Nostr user") },
        text = {
            Column {
                Text(
                    text = "This message will be re-encrypted and delivered to the " +
                        "recipient's key, marked as forwarded by you.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(12.dp))
                DesentTextField(
                    value = npub,
                    onValueChange = { npub = it },
                    label = { Text("Recipient npub") },
                    placeholder = { Text("npub1…") },
                    singleLine = true,
                    isError = npub.isNotBlank() && !isValid,
                    enabled = !isSending,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(npub.trim()) },
                enabled = isValid && !isSending
            ) {
                if (isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Forward")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSending
            ) { Text("Cancel") }
        }
    )
}
