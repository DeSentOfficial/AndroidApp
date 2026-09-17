package xyz.desent.presentation.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

/**
 * A compact label/value stat tile used inside summary card rows.
 *
 * @param value The emphasized value (e.g. "12,300", "3").
 * @param label The muted caption beneath the value (e.g. "Balance", "Unread").
 * @param modifier Slot for layout weighting within the parent row.
 * @param highlight When true, renders the value in [highlightColor] when
 *                  provided, else the error color — use to draw attention to
 *                  things like unread or spam counts.
 * @param valueColor Optional explicit color for the value; overrides highlight.
 * @param highlightColor Optional caller-provided highlight color (e.g. the
 *                       owning card's accent).
 */
@Composable
fun SummaryStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    valueColor: Color? = null,
    highlightColor: Color? = null
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.Start) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            color = valueColor ?: highlightColor
                ?: if (highlight) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
