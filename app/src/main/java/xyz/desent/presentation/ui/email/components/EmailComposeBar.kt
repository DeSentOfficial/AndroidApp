package xyz.desent.presentation.ui.email.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import xyz.desent.domain.model.Account
import xyz.desent.domain.model.EmailBodyFormat
import xyz.desent.presentation.theme.Spacing
import xyz.desent.presentation.ui.components.EditorBarIcon
import xyz.desent.presentation.ui.components.EditorBottomBar
import xyz.desent.presentation.ui.email.viewmodel.PgpComposeLock

/**
 * The compose/reply bottom bar: the active account's avatar outside on the
 * left, then a rounded-square options box carrying — in HTML mode — the
 * rich-text actions (bold / italic / underline / bullet list / clipboard
 * link) plus the PGP lock. Plain-text mode drops the formatting group but
 * keeps link insertion ([onInsertPlainText], a bare URL appended to the
 * draft) so "add a link" works regardless of the email's format.
 *
 * [pgpHint] is the PGP reason line (multi-recipient cap, missing key, WKD
 * miss…) rendered directly above the bar — anchored to the lock it explains
 * rather than floating below the editor.
 *
 * Formatting actions drive the [RichTextEditor] through [editorState]; the
 * link action wraps the selection in a link to the clipboard URL (same
 * clipboard contract the editor's old toolbar used).
 */
@Composable
fun EmailComposeBottomBar(
    account: Account?,
    format: EmailBodyFormat,
    editorState: RichTextEditorState,
    pgp: PgpComposeLock.State?,
    pgpHint: String? = null,
    enabled: Boolean,
    onPgpToggle: () -> Unit,
    onPgpNeedKey: () -> Unit,
    modifier: Modifier = Modifier,
    onInsertPlainText: ((String) -> Unit)? = null
) {
    val clipboard = LocalClipboardManager.current
    Column {
        pgpHint?.let { hint ->
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)
            )
        }
        EditorBottomBar(account = account, modifier = modifier) {
            if (format == EmailBodyFormat.HTML) {
                EditorBarIcon(onClick = { editorState.bold() }, enabled = enabled) {
                    Text(
                        text = "B",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                EditorBarIcon(onClick = { editorState.italic() }, enabled = enabled) {
                    Text(
                        text = "I",
                        style = MaterialTheme.typography.titleMedium,
                        fontStyle = FontStyle.Italic,
                        fontFamily = FontFamily.Serif
                    )
                }
                EditorBarIcon(onClick = { editorState.underline() }, enabled = enabled) {
                    Text(
                        text = "U",
                        style = MaterialTheme.typography.titleMedium,
                        textDecoration = TextDecoration.Underline
                    )
                }
                EditorBarIcon(onClick = { editorState.bulletList() }, enabled = enabled) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.FormatListBulleted,
                        contentDescription = "Bullet list",
                        modifier = Modifier.size(22.dp)
                    )
                }
                EditorBarIcon(
                    onClick = {
                        clipboard.getText()?.text?.takeIf { it.isNotBlank() }?.let { url ->
                            editorState.insertLink(url)
                        }
                    },
                    enabled = enabled
                ) {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = "Insert link from clipboard",
                        modifier = Modifier.size(22.dp)
                    )
                }
            } else {
                // TXT mode: no formatting, but a bare-URL link insertion stays
                // available so both email types can "add" the same basics.
                onInsertPlainText?.let { insert ->
                    EditorBarIcon(
                        onClick = {
                            clipboard.getText()?.text?.takeIf { it.isNotBlank() }?.let(insert)
                        },
                        enabled = enabled
                    ) {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = "Insert link from clipboard",
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
            pgp?.takeIf { it.visible }?.let { pgpState ->
                PgpLockButton(
                    state = pgpState,
                    enabled = enabled,
                    onToggle = onPgpToggle,
                    onNeedKey = onPgpNeedKey
                )
            }
        }
    }
}

/**
 * The compact HTML/TXT format chip that lives in the top bar, to the left of
 * Send. Shows the current body format; tapping switches between the two (the
 * ViewModel converts the body so nothing typed is lost).
 */
@Composable
fun FormatChip(
    format: EmailBodyFormat,
    enabled: Boolean = true,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 32.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (format == EmailBodyFormat.HTML) "HTML" else "TXT",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
