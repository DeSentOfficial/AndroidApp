package xyz.desent.presentation.ui.email.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.desent.domain.model.EmailRecipient
import xyz.desent.presentation.theme.Spacing

/**
 * One To/Cc/Bcc line of the compose surface (END-01 §3.4): committed
 * recipients as removable chips with an inline type-target at the end —
 * typing a comma/semicolon commits a chip (see RecipientFieldsController).
 * The display name from a contact-picked suggestion rides the chip label.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecipientChipField(
    label: String,
    recipients: List<EmailRecipient>,
    text: String,
    onTextChange: (String) -> Unit,
    onRemoveRecipient: (EmailRecipient) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    placeholder: String,
    enabled: Boolean,
    interactionSource: MutableInteractionSource,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterVertically)
        )
        recipients.forEach { recipient ->
            InputChip(
                selected = false,
                onClick = { onRemoveRecipient(recipient) },
                enabled = enabled,
                label = {
                    Text(
                        text = recipient.displayName?.takeIf { it.isNotBlank() } ?: recipient.address,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Remove ${recipient.address}",
                        modifier = Modifier.padding(0.dp)
                    )
                }
            )
        }
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            enabled = enabled,
            singleLine = true,
            textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            interactionSource = interactionSource,
            decorationBox = { inner ->
                if (text.isEmpty()) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.align(Alignment.CenterVertically)
                    ) {
                        inner()
                        Text(
                            text = placeholder,
                            style = textStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .padding(bottom = 1.dp)
                    ) {
                        inner()
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.CenterVertically)
                .padding(start = 2.dp)
                .onFocusChanged { onFocusChanged(it.isFocused) }
        )
    }
}
