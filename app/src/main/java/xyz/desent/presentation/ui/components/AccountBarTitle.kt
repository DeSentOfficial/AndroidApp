package xyz.desent.presentation.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import xyz.desent.di.AppContainer

/**
 * Top-app-bar title showing the active account's [xyz.desent.domain.model.Account.shortLabel]
 * (display name → nip05 local-part → truncated npub) — mirrors the web header,
 * which anchors the bar on the account rather than the screen name. The logo
 * mark stays in the navigation slot; screens announce themselves through
 * content, not the title.
 */
@Composable
fun AccountBarTitle() {
    val context = LocalContext.current
    val appContainer = remember { AppContainer.getInstance(context) }
    val account by appContainer.sessionManager.activeAccount.collectAsState()
    Text(text = account?.shortLabel ?: "DeSent")
}
