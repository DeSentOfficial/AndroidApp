package xyz.desent.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import xyz.desent.domain.model.Account

/**
 * The active account's avatar in [AvatarShape]: the profile picture when one
 * exists, else a deterministic initials tile — the same fallback family as
 * the bottom navigation bar and the account switcher. Editor screens show it
 * beside the writing actions so the user can always see who they're writing
 * as.
 */
@Composable
fun AccountAvatar(
    account: Account?,
    size: Dp,
    contentDescription: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val picture = account?.picture
    if (picture != null) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(picture).crossfade(true).build(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(AvatarShape)
        )
    } else {
        // Initials fallback so the avatar is visible even before the kind-0
        // profile picture has loaded, or for accounts that have none.
        val initials = account?.shortLabel?.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        Box(
            modifier = modifier
                .size(size)
                .clip(AvatarShape)
                .background(avatarTintFor(account?.npub ?: "")),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initials,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}
