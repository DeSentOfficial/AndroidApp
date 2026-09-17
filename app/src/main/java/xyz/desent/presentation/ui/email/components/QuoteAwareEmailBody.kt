package xyz.desent.presentation.ui.email.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.desent.data.spam.RemoteImagePolicyState
import xyz.desent.domain.model.Email

/**
 * Email body that collapses the quoted reply history behind a toggle.
 *
 * Inbound replies from real mail clients embed the entire prior conversation
 * (Gmail quote divs, Outlook header blocks, `>` chains); rendering each of
 * them in full made threads into walls of duplicated quoted text. The new
 * content renders immediately; the recognized quoted tail renders only on
 * expansion. Bodies without a recognized quote marker render whole, and the
 * expansion state survives recomposition and process death per message id.
 */
@Composable
fun QuoteAwareEmailBody(
    email: Email,
    imagePolicy: RemoteImagePolicyState,
    modifier: Modifier = Modifier,
    wrapContentHeight: Boolean = true
) {
    val split = remember(email.id, email.content, email.bodyFormat) {
        EmailQuoteSplitter.split(email.content, email.bodyFormat)
    }
    val quoted = split.quotedTail

    if (quoted == null) {
        PolicyAwareEmailBody(
            email = email,
            imagePolicy = imagePolicy,
            modifier = modifier,
            wrapContentHeight = wrapContentHeight
        )
        return
    }

    var expanded by rememberSaveable(email.id) { mutableStateOf(false) }

    Column(modifier = modifier.animateContentSize()) {
        PolicyAwareEmailBody(
            email = email,
            imagePolicy = imagePolicy,
            modifier = Modifier.fillMaxWidth(),
            wrapContentHeight = wrapContentHeight,
            contentOverride = split.newContent
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable { expanded = !expanded }
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = if (expanded) "Hide quoted content" else "Show quoted content",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (expanded) {
            PolicyAwareEmailBody(
                email = email,
                imagePolicy = imagePolicy,
                modifier = Modifier.fillMaxWidth(),
                wrapContentHeight = wrapContentHeight,
                contentOverride = quoted
            )
        }
    }
}
