package xyz.desent.presentation.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.desent.R

/**
 * The DeSent logo mark, shown at the top-left of every main-level (home)
 * screen in place of a back arrow — those screens sit at the root of the tab
 * stack and never navigate up.
 */
@Composable
fun DesentLogoMark(
    size: Dp = 24.dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = "DeSent"
) {
    Icon(
        painter = painterResource(R.drawable.ic_desent_logo),
        contentDescription = contentDescription,
        tint = Color.Unspecified,
        modifier = modifier.size(size)
    )
}
