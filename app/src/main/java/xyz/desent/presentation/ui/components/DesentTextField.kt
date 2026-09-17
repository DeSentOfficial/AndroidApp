package xyz.desent.presentation.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import xyz.desent.presentation.theme.InputWellDark
import xyz.desent.presentation.theme.InputWellLight

/**
 * The DeSent text field — a drop-in `OutlinedTextField` with the app's
 * "input well" styling so fields read as active, editable surfaces instead
 * of grey outlined cages:
 *
 * - inset filled container (darker than the card in dark mode, white in
 *   light mode — see `InputWellDark`/`InputWellLight`),
 * - a ghost hairline border at rest that keeps the well visible on any host
 *   surface, and a full 2dp `primary` border + label on focus,
 * - brighter resting labels than the M3 `onSurfaceVariant` grey,
 * - 10dp (`shapes.medium`) corners.
 *
 * `isError` semantics are preserved. Pass [colors]/[shape] to override.
 */
@Composable
fun DesentTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape? = null,
    colors: TextFieldColors? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        prefix = prefix,
        suffix = suffix,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        interactionSource = interactionSource,
        shape = shape ?: MaterialTheme.shapes.medium,
        colors = colors ?: desentTextFieldColors()
    )
}

/** The default input-well palette (theme-role based, both modes). */
@Composable
fun desentTextFieldColors(): TextFieldColors {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = scheme.onSurface,
        unfocusedTextColor = scheme.onSurface,
        disabledTextColor = scheme.onSurface.copy(alpha = 0.38f),
        focusedContainerColor = if (dark) InputWellDark else InputWellLight,
        unfocusedContainerColor = if (dark) InputWellDark else InputWellLight,
        cursorColor = scheme.primary,
        errorCursorColor = scheme.error,
        focusedBorderColor = scheme.primary,
        unfocusedBorderColor = scheme.outline.copy(alpha = 0.30f),
        disabledBorderColor = scheme.onSurface.copy(alpha = 0.12f),
        errorBorderColor = scheme.error,
        focusedLabelColor = scheme.primary,
        unfocusedLabelColor = scheme.onSurface.copy(alpha = 0.72f),
        disabledLabelColor = scheme.onSurface.copy(alpha = 0.38f),
        errorLabelColor = scheme.error,
        focusedPlaceholderColor = scheme.onSurfaceVariant,
        unfocusedPlaceholderColor = scheme.onSurfaceVariant,
        focusedLeadingIconColor = scheme.onSurfaceVariant,
        unfocusedLeadingIconColor = scheme.onSurfaceVariant,
        focusedTrailingIconColor = scheme.primary,
        unfocusedTrailingIconColor = scheme.onSurfaceVariant,
        focusedSupportingTextColor = scheme.onSurfaceVariant,
        unfocusedSupportingTextColor = scheme.onSurfaceVariant
    )
}
