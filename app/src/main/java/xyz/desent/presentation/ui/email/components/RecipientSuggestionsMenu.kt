package xyz.desent.presentation.ui.email.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import xyz.desent.presentation.ui.contacts.ContactAvatar
import xyz.desent.presentation.ui.email.viewmodel.RecipientSuggestion
import xyz.desent.presentation.theme.Spacing

/**
 * The To-field contact autocomplete dropdown: a floating menu anchored under
 * the recipient row (default M3 menu styling, like the other app menus).
 * Rows are one-per-email-address with the contact's avatar (cached nostr
 * picture or initials), the address, and the slot label when the contact
 * has more than one address.
 */
@Composable
fun RecipientSuggestionsMenu(
    suggestions: List<RecipientSuggestion>,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onSuggestionSelected: (RecipientSuggestion) -> Unit,
    modifier: Modifier = Modifier
) {
    DropdownMenu(
        expanded = expanded && suggestions.isNotEmpty(),
        onDismissRequest = onDismissRequest,
        // Non-focusable: a focusable popup would take window focus from the
        // To field on show, dropping the IME mid-typing.
        properties = PopupProperties(focusable = false),
        modifier = modifier.fillMaxWidth()
    ) {
        suggestions.forEach { suggestion ->
            DropdownMenuItem(
                text = {
                    Column {
                        Text(
                            text = suggestion.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = suggestion.email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                leadingIcon = {
                    ContactAvatar(
                        contact = suggestion.contact,
                        pictureUrl = suggestion.pictureUrl,
                        size = 36.dp
                    )
                },
                trailingIcon = {
                    // Only meaningful when the contact has 2+ addresses —
                    // otherwise every row would carry a redundant chip.
                    if (suggestion.contact.allEmails().size > 1 && suggestion.label.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = Spacing.sm, vertical = 2.dp)
                        ) {
                            Text(
                                text = suggestion.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                onClick = { onSuggestionSelected(suggestion) }
            )
        }
    }
}
