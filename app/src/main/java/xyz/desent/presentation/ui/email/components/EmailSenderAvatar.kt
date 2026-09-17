package xyz.desent.presentation.ui.email.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import xyz.desent.presentation.ui.components.AvatarShape
import xyz.desent.presentation.ui.components.avatarTintFor
import xyz.desent.presentation.ui.components.darken
import xyz.desent.presentation.ui.components.senderInitials

/**
 * Square brand avatar ([AvatarShape], never a circle) for an email sender:
 * the nostr kind-0 `picture` when one resolves, else initials on a
 * deterministic per-sender tint. Shared by the inbox/spam rows and the
 * detail headers.
 */
@Composable
fun EmailSenderAvatar(
    displayName: String,
    seed: String,
    pictureUrl: String?,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val initials = remember(displayName, seed) { senderInitials(displayName, seed) }
    val tint = remember(seed) { avatarTintFor(seed) }
    val gradient = remember(tint) {
        Brush.linearGradient(listOf(tint, tint.darken(0.25f)))
    }

    Box(
        modifier = modifier
            .size(size)
            .background(gradient, AvatarShape),
        contentAlignment = Alignment.Center
    ) {
        if (pictureUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(pictureUrl).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(size)
                    .clip(AvatarShape),
                error = null // failed loads reveal the initials underneath
            )
        } else {
            Text(
                text = initials,
                color = Color.White,
                fontSize = (size.value * 0.38f).sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

