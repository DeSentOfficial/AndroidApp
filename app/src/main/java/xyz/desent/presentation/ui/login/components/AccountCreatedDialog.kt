package xyz.desent.presentation.ui.login.components

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import xyz.desent.domain.model.AccountCreationResult
import xyz.desent.presentation.theme.Spacing

@Composable
fun AccountCreatedDialog(
    account: AccountCreationResult,
    hasAcknowledgedBackup: Boolean,
    onAcknowledgeBackup: () -> Unit,
    onContinue: () -> Unit,
    /** True when the account was created with a username & password: the exported key is the ONLY recovery path if the password is forgotten. */
    isCustodial: Boolean = false
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(Spacing.lg)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(48.dp)
                )

                Spacer(modifier = Modifier.height(Spacing.md))

                Text(
                    text = "Account Created!",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(Spacing.sm))

                Text(
                    text = "⚠️ Save your keys securely!\nNever share your private key!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(Spacing.md))

                if (isCustodial) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = "There is NO password reset. If you forget your " +
                                "password, this exported key is your only way back in. " +
                                "Save it somewhere safe.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(Spacing.md)
                        )
                    }

                    Spacer(modifier = Modifier.height(Spacing.md))
                }

                KeyDisplayField(
                    label = "Private Key (nsec)",
                    value = account.nsec,
                    onCopy = {
                        clipboardManager.setText(AnnotatedString(account.nsec))
                        Toast.makeText(context, "nsec copied!", Toast.LENGTH_SHORT).show()
                    }
                )

                Spacer(modifier = Modifier.height(Spacing.sm))

                KeyDisplayField(
                    label = "Public Key (npub)",
                    value = account.npub,
                    onCopy = {
                        clipboardManager.setText(AnnotatedString(account.npub))
                        Toast.makeText(context, "npub copied!", Toast.LENGTH_SHORT).show()
                    }
                )

                if (isCustodial) {
                    Spacer(modifier = Modifier.height(Spacing.md))

                    // Standard-format, passphrase-protected key backup —
                    // safer to store than the raw nsec above and importable
                    // by any NIP-49 client (CUSTODIAL_ACCOUNTS.md §7).
                    Text(
                        text = "Encrypted key backup (recommended)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(
                        text = "Protect your key with a separate passphrase. Store the " +
                            "result anywhere; it works in any Nostr app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    xyz.desent.presentation.ui.components.NcryptsecExportPanel(
                        nsec = account.nsec
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.md))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = "To create an encrypted backup you can store on your phone or in the cloud, use Settings → Backup accounts after continuing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Spacing.md)
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.md))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = hasAcknowledgedBackup,
                        onCheckedChange = { onAcknowledgeBackup() }
                    )
                    Text(
                        text = "I've saved my key securely",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.md))

                Button(
                    onClick = onContinue,
                    enabled = hasAcknowledgedBackup,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Continue to DeSent")
                }
            }
        }
    }
}

@Composable
private fun KeyDisplayField(
    label: String,
    value: String,
    onCopy: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(Spacing.xs))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small
        ) {
            Row(
                modifier = Modifier.padding(Spacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SelectionContainer {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        modifier = Modifier.weight(1f)
                    )
                }

                IconButton(onClick = onCopy) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                }
            }
        }
    }
}

