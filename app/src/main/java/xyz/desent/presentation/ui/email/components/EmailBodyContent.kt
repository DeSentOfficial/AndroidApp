package xyz.desent.presentation.ui.email.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import xyz.desent.domain.model.EmailBodyFormat

/**
 * Renders an email body in its wire format (NIP-EMAIL `["format", …]` tag):
 * HTML → [HtmlContent] (locked-down WebView); PLAIN → newline-preserving
 * Compose text. Legacy rows (no tag) render as HTML, matching how kind-14
 * bodies always rendered.
 */
@Composable
fun EmailBodyContent(
    format: EmailBodyFormat,
    content: String,
    modifier: Modifier = Modifier,
    wrapContentHeight: Boolean = false,
    blockRemoteImages: Boolean = false
) {
    if (content.isBlank()) return
    when (format) {
        EmailBodyFormat.PLAIN -> Text(
            text = content,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Default),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = modifier
        )
        EmailBodyFormat.HTML -> HtmlContent(
            html = content,
            modifier = modifier,
            wrapContentHeight = wrapContentHeight,
            blockRemoteImages = blockRemoteImages
        )
    }
}
