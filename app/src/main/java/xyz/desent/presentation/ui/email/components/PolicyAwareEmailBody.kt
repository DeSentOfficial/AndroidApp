package xyz.desent.presentation.ui.email.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.desent.R
import xyz.desent.data.spam.RemoteImagePolicyState
import xyz.desent.data.spam.TrackingPixelDetector
import xyz.desent.domain.model.Email
import xyz.desent.presentation.theme.Spacing

/**
 * Email body renderer that applies the remote-image policy
 * (see [RemoteImagePolicyState]).
 *
 * - Sender allowed (allowlist / contacts / master off) → renders directly, no banner.
 * - Sender blocked → shows a banner (only when the body actually references
 *   remote images) with three actions: "Load images" (this message only),
 *   "Always allow sender", and "Always allow domain". The latter two write to
 *   the cloud-synced spam config, so the change propagates to every device.
 *
 * The WebView is keyed on (message, effective blocking) so toggling either
 * recreates it with the new [HtmlContent.blockRemoteImages] setting.
 */
@Composable
fun PolicyAwareEmailBody(
    email: Email,
    imagePolicy: RemoteImagePolicyState,
    modifier: Modifier = Modifier,
    wrapContentHeight: Boolean = true,
    /** Renders this content instead of [Email.content] (quote splitting). */
    contentOverride: String? = null
) {
    val snapshot by imagePolicy.snapshot.collectAsState()
    val content = contentOverride ?: email.content

    // Per-message session override from the "Load images" action.
    var sessionLoaded by rememberSaveable(email.id) { mutableStateOf(false) }
    val blockedByPolicy = snapshot.isBlocked(email.senderEmail)
    val effectiveBlock = blockedByPolicy && !sessionLoaded

    Column(modifier = modifier) {
        // Static scan — shown even when images are allowed/loaded, because the
        // detection itself doesn't depend on whether the pixels ever fetch.
        val pixelScan = remember(content) { TrackingPixelDetector.scan(content) }
        if (pixelScan.hasTrackingPixels) {
            TrackingPixelsBanner(pixelCount = pixelScan.pixelCount)
            Spacer(Modifier.height(Spacing.xs))
        }

        if (effectiveBlock && hasRemoteImages(content)) {
            RemoteImagesBanner(
                onLoadImages = { sessionLoaded = true },
                onAlwaysAllowSender = { imagePolicy.allowSender(email.senderEmail) },
                onAlwaysAllowDomain = { imagePolicy.allowDomain(email.senderEmail) }
            )
            Spacer(Modifier.height(Spacing.xs))
        }

        key(email.id, content, effectiveBlock) {
            EmailBodyContent(
                format = email.bodyFormat,
                content = content,
                blockRemoteImages = effectiveBlock,
                wrapContentHeight = wrapContentHeight,
                modifier = if (wrapContentHeight) {
                    Modifier.fillMaxWidth()
                } else {
                    // Fill the space the caller gave this column below the banner.
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                }
            )
        }
    }
}

/** Banner shown above a blocked body. Pure UI; actions provided by the caller. */
@Composable
private fun RemoteImagesBanner(
    onLoadImages: () -> Unit,
    onAlwaysAllowSender: () -> Unit,
    onAlwaysAllowDomain: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = "Remote images are blocked",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onLoadImages,
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 0.dp)
                ) { Text("Load images", style = MaterialTheme.typography.labelSmall) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                TextButton(
                    onClick = onAlwaysAllowSender,
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp),
                    contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 0.dp)
                ) { Text("Always allow sender", maxLines = 1, style = MaterialTheme.typography.labelSmall) }
                TextButton(
                    onClick = onAlwaysAllowDomain,
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp),
                    contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 0.dp)
                ) { Text("Always allow domain", maxLines = 1, style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

/**
 * Notice shown when the body contains tracking pixels. Informational only —
 * the block/allow actions live on [RemoteImagesBanner].
 */
@Composable
private fun TrackingPixelsBanner(pixelCount: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Visibility,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(Spacing.sm))
            Column {
                Text(
                    text = if (pixelCount == 1) {
                        stringResource(R.string.tracking_pixel_banner_single)
                    } else {
                        stringResource(R.string.tracking_pixel_banner_multiple, pixelCount)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    text = stringResource(R.string.tracking_pixel_banner_caption),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
    }
}

/**
 * Heuristic: does this HTML body reference any remote (http/https) image or
 * background? Cheap lowercase scan — deliberately over-broad (it only gates
 * whether the banner is worth showing).
 */
internal fun hasRemoteImages(html: String): Boolean {
    val lower = html.lowercase()
    return lower.contains("src=\"http") || lower.contains("src='http") ||
        lower.contains("src=http") || lower.contains("url(http") ||
        lower.contains("url('http") || lower.contains("url(\"http") ||
        lower.contains("background=\"http") || lower.contains("background='http")
}
