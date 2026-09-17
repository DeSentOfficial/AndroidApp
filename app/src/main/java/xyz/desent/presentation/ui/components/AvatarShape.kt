package xyz.desent.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.desent.R

/**
 * The canonical shape for every user profile picture (and its fallback) in
 * DeSent: a square with rounded edges, corner radius = 28% of the side.
 *
 * This matches the bottom navigation bar's avatar exactly (8dp corners on a
 * 28dp icon) and scales proportionally across every avatar size in the app
 * (22dp Wear feed rows up to the 108dp profile card hero). Never render a
 * user avatar as a circle or oval — badges, favorite chips and other overlay
 * indicators stay circular, while presence rings and security rings stroke
 * this same shape around the photo slot.
 *
 * See refs/branding/desent-brand.html "Profile Pictures" for the full spec.
 */
val AvatarShape = RoundedCornerShape(percent = 28)

/**
 * Branded fallback for profiles without a picture: the DS speech-bubble mark
 * with a person silhouette punched through as negative space
 * (ic_avatar_placeholder), on a surfaceVariant tile clipped to [AvatarShape].
 *
 * The drawable itself is theme-aware (body: classic blue / amber; knockout:
 * light / dark surfaceVariant), so no per-call-site tinting is needed. Sites
 * that already know the user's name (bottom bar, account switcher, Wear)
 * keep their deterministic initials discs instead — this composable is for
 * anonymous/unknown or empty-picture slots.
 */
@Composable
fun AvatarPlaceholder(
    size: Dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(AvatarShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_avatar_placeholder),
            contentDescription = null,
            modifier = Modifier.size(size)
        )
    }
}
