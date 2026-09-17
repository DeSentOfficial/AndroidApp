package xyz.desent.presentation.ui.components

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.desent.crypto.Bech32Utils
import xyz.desent.crypto.Nip44Encryption
import xyz.desent.crypto.Nip49
import xyz.desent.presentation.theme.Spacing
import xyz.desent.presentation.ui.shared.QRCodeImage

private const val PASSPHRASE_MIN = 8

/**
 * NIP-49 encrypted-key export (CUSTODIAL_ACCOUNTS.md §8 / §7 of the Android
 * guide): encrypts the account's key under a passphrase of the user's choice
 * (it may differ from the account password) into a standard `ncryptsec1…`
 * string importable by any conformant Nostr client. The scrypt KDF runs off
 * the main thread; the passphrase is cleared from the field as soon as the
 * ciphertext exists.
 */
@Composable
fun NcryptsecExportPanel(
    nsec: String,
    modifier: Modifier = Modifier
) {
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var passphraseVisible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var generating by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var showQr by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.openOutputStream(it)?.use { os ->
                    os.write(result.orEmpty().toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(context, "Encrypted key saved", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val ncryptsec = result
    if (ncryptsec == null) {
        Column(modifier = modifier) {
            DesentTextField(
                value = passphrase,
                onValueChange = {
                    passphrase = it
                    error = null
                },
                label = { Text("Backup passphrase") },
                singleLine = true,
                visualTransformation = if (passphraseVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passphraseVisible = !passphraseVisible }) {
                        Icon(
                            if (passphraseVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (passphraseVisible) "Hide" else "Show"
                        )
                    }
                },
                isError = error != null,
                supportingText = error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                enabled = !generating,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(Spacing.sm))

            DesentTextField(
                value = confirm,
                onValueChange = {
                    confirm = it
                    error = null
                },
                label = { Text("Confirm passphrase") },
                singleLine = true,
                visualTransformation = if (passphraseVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
                isError = error != null,
                enabled = !generating,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(Spacing.md))

            Button(
                onClick = {
                    when {
                        passphrase.length < PASSPHRASE_MIN ->
                            error = "At least $PASSPHRASE_MIN characters"
                        passphrase != confirm ->
                            error = "Passphrases do not match"
                        else -> {
                            generating = true
                            error = null
                            scope.launch {
                                val encrypted = withContext(Dispatchers.Default) {
                                    runCatching {
                                        Nip49.encrypt(Nip44Encryption.hexToBytes(Bech32Utils.nsecToHex(nsec)), passphrase)
                                    }
                                }
                                // The passphrase has served its purpose — clear the fields.
                                passphrase = ""
                                confirm = ""
                                generating = false
                                encrypted.fold(
                                    onSuccess = { result = it },
                                    onFailure = { error = "Encryption failed: ${it.message}" }
                                )
                            }
                        }
                    }
                },
                enabled = !generating,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (generating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Create encrypted backup")
                }
            }
        }
    } else {
        Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small
            ) {
                SelectionContainer {
                    Text(
                        text = ncryptsec,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(Spacing.sm)
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.sm))

            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(ncryptsec))
                    Toast.makeText(context, "Encrypted key copied", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(modifier = Modifier.size(Spacing.xs))
                    Text("Copy")
                }
                OutlinedButton(onClick = { fileLauncher.launch("desent-key-ncryptsec.txt") }) {
                    Icon(Icons.Default.IosShare, contentDescription = null)
                    Spacer(modifier = Modifier.size(Spacing.xs))
                    Text("Save")
                }
                OutlinedButton(onClick = { showQr = !showQr }) {
                    Icon(Icons.Default.QrCode2, contentDescription = null)
                    Spacer(modifier = Modifier.size(Spacing.xs))
                    Text("QR")
                }
            }

            if (showQr) {
                Spacer(modifier = Modifier.height(Spacing.md))
                QRCodeImage(content = ncryptsec, size = 220.dp)
            }

            Spacer(modifier = Modifier.height(Spacing.sm))

            Text(
                text = "This ncryptsec opens with the passphrase you chose. Any Nostr " +
                    "app that supports NIP-49 can import it — store the two separately.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
