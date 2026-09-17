package xyz.desent.presentation.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import xyz.desent.presentation.theme.Spacing

/**
 * The "open canvas" text field used by the redesigned text-entry screens
 * (compose / reply / note editor): no container fill, no indicator, no label
 * — just the text, an optional muted placeholder, and an optional small
 * leading label (e.g. "To"). Replaces the boxed [DesentTextField] on those
 * screens so the writing surface reads as one continuous page.
 */
@Composable
fun BorderlessTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    leadingLabel: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    interactionSource: MutableInteractionSource? = null
) {
    // The String facade wraps a TextFieldValue so the cursor survives
    // mid-text edits; the text is only re-synced (cursor sent to the end)
    // when the value changed from outside the field, e.g. recipient
    // prefill or a reply quote seeding.
    var fieldValue by remember { mutableStateOf(TextFieldValue(value)) }
    if (value != fieldValue.text) {
        fieldValue = TextFieldValue(value, selection = TextRange(value.length))
    }
    BorderlessTextField(
        value = fieldValue,
        onValueChange = {
            fieldValue = it
            onValueChange(it.text)
        },
        modifier = modifier,
        placeholder = placeholder,
        leadingLabel = leadingLabel,
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        textStyle = textStyle,
        interactionSource = interactionSource
    )
}

@Composable
fun BorderlessTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    leadingLabel: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    interactionSource: MutableInteractionSource? = null
) {
    // Caller-supplied source lets the screen observe focus (e.g. the compose
    // screen arms its recipient dropdown on focus); otherwise a private one.
    val source = interactionSource ?: remember { MutableInteractionSource() }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        leadingLabel?.let { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(Spacing.sm))
        }
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            enabled = enabled,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            textStyle = textStyle,
            placeholder = placeholder?.let { p ->
                {
                    Text(
                        text = p,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = textStyle
                    )
                }
            },
            interactionSource = source,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent
            )
        )
    }
}
