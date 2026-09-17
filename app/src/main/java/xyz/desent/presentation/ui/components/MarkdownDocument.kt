package xyz.desent.presentation.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.coil2.Coil2ImageTransformerImpl
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownPadding
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.markdownPadding
import xyz.desent.presentation.theme.Spacing

/**
 * Markdown document body shared by the private-notes editor and the device
 * markdown viewer, rendered with DeSent's standardized document typography
 * and spacing (the library's m3 defaults map headings to the `display*`
 * styles, producing 57sp h1s against 16sp body text) and with the Coil2
 * transformer so inline images actually load.
 */
@Composable
fun MarkdownDocument(
    content: String,
    modifier: Modifier = Modifier
) {
    Markdown(
        content = content,
        typography = markdownDocumentTypography(),
        padding = markdownDocumentPadding(),
        imageTransformer = Coil2ImageTransformerImpl,
        modifier = modifier
    )
}

/** Document-oriented type scale anchored to the M3 theme: body 16sp, h1 24sp, stepping down ~2sp per level. */
@Composable
fun markdownDocumentTypography(): MarkdownTypography = markdownTypography(
    h1 = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    h2 = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    h3 = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Medium),
    h4 = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    h5 = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    h6 = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
    text = MaterialTheme.typography.bodyLarge,
    paragraph = MaterialTheme.typography.bodyLarge,
    quote = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
    code = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
    inlineCode = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
    ordered = MaterialTheme.typography.bodyLarge,
    bullet = MaterialTheme.typography.bodyLarge,
    list = MaterialTheme.typography.bodyLarge,
    link = MaterialTheme.typography.bodyLarge.copy(
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Medium
    ),
    table = MaterialTheme.typography.bodyMedium
)

/** Block/list/quote spacing on the app's [Spacing] tokens for consistent rhythm. */
@Composable
fun markdownDocumentPadding(): MarkdownPadding = markdownPadding(
    block = Spacing.sm,
    list = Spacing.xs,
    listItemTop = Spacing.xs,
    listItemBottom = Spacing.xs,
    listIndent = Spacing.lg,
    codeBlock = PaddingValues(Spacing.md),
    blockQuote = PaddingValues(horizontal = Spacing.md),
    blockQuoteText = PaddingValues(vertical = Spacing.xs)
)
