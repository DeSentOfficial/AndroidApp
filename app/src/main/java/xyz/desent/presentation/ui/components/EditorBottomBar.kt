package xyz.desent.presentation.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import xyz.desent.domain.model.Account
import xyz.desent.presentation.theme.Spacing

/**
 * The text-entry screens' bottom bar (email compose, reply, note editor):
 *
 *   [avatar]  [ rounded-square options box ]
 *
 * The active account's avatar stands **outside** the box, to its left —
 * identity, not a button. The box is a rounded square whose corner radius is
 * 28% of its height, the same geometry family as [AvatarShape], and hosts the
 * screen's writing actions via [content].
 */
@Composable
fun EditorBottomBar(
    account: Account?,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AccountAvatar(account = account, size = 38.dp, contentDescription = "Composing as")
        Spacer(Modifier.width(10.dp))
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier
                .weight(1f)
                .height(58.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
                content = content
            )
        }
    }
}

/**
 * A single action inside the [EditorBottomBar] options box: a 42dp
 * rounded-square tap target. [active] tints the content with the accent
 * (used for the engaged PGP lock); the resting tint is muted.
 */
@Composable
fun EditorBarIcon(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalContentColor provides if (active) {
            MaterialTheme.colorScheme.secondary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    ) {
        Box(
            modifier = modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
            content = { content() }
        )
    }
}
