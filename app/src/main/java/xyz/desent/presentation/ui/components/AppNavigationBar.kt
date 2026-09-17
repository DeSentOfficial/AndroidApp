package xyz.desent.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import xyz.desent.di.AppContainer
import xyz.desent.domain.model.Account
import xyz.desent.presentation.theme.Amber
import xyz.desent.presentation.ui.accounts.AccountSwitcherSheet

/**
 * The shared bottom navigation bar used by every main-level destination.
 *
 * - The first item is the **active account's avatar** (unlabelled, but
 *   sized and row-aligned with the other tabs' icons).
 *   Tapping it always opens the account switcher sheet (account details,
 *   edit account, bunker connections, security alerts, backup, and account
 *   switching live there).
 * - The middle tabs are fixed: Email (carries the unread badge), Calendar
 *   (hidden while the relay's `calendar_enabled` flag is off) and Notes.
 * - The final "More" tab opens an upward-expanding overflow menu holding
 *   Storage, Files, Contacts and Settings.
 * - Selection is conveyed by icon + label color alone; the M3 default
 *   indicator is neutralized (it is a fixed 64dp pill behind the icon only
 *   — it bleeds past the 42dp avatar and never touches the text). The
 *   avatar tab carries no label; while unseen login-security alerts exist
 *   the avatar itself gets the hard Amber shield ring + chip.
 */
@Composable
fun AppNavigationBar(
    selectedRoute: String,
    unreadEmailCount: Int,
    /**
     * Unseen login-security alerts for the active account. Drives the hard
     * Amber ring + shield chip on the avatar tab (and the badge on the
     * Security row in the account switcher sheet), so the user knows there's
     * a security notification waiting in the background.
     */
    unseenSecurityCount: Int,
    onNavigateEmails: () -> Unit,
    onNavigateFiles: () -> Unit,
    onNavigateContacts: () -> Unit,
    onNavigateNotes: () -> Unit,
    onNavigateCalendar: () -> Unit,
    onNavigateStorage: () -> Unit,
    onNavigateSettings: () -> Unit,
    onNavigateToAddAccount: () -> Unit,
    /**
     * Opens the profile editor for the active account (account switcher
     * drill-in).
     */
    onEditAccount: () -> Unit = {},
    /**
     * Opens bunker connections scoped to [npub] (account switcher drill-in).
     */
    onOpenBunkerConnections: (npub: String) -> Unit = {},
    /**
     * Opens the Security alerts screen scoped to [npub] (login-security
     * notifications + alert-mode configuration for that account).
     */
    onOpenSecurityAlerts: (npub: String) -> Unit,
    /**
     * Opens the backup wizard with [npub] preselected (account switcher
     * per-account section).
     */
    onOpenBackup: (npub: String) -> Unit,

    /** Opens PGP key management (active account's key). */
    onOpenPgpSettings: () -> Unit = {},
    /**
     * Invoked after an account switch completes successfully. The caller MUST
     * use this to destroy and recreate the Inbox destination (e.g.
     * `popUpTo(Emails.Inbox.route) { inclusive = true }`) so that
     * screen-level ViewModels — which capture the active npub at init — are
     * rebuilt for the new account. Without this, every screen-level
     * ViewModel retains the previous account's npub and shows stale
     * follows / DMs / emails until the process is killed.
     */
    onAccountSwitched: () -> Unit
) {
    val context = LocalContext.current
    val appContainer = remember { AppContainer.getInstance(context) }
    val scope = rememberCoroutineScope()

    val activeAccount by appContainer.sessionManager.activeAccount.collectAsState()
    val accounts by appContainer.sessionManager.accounts.collectAsState()
    val activeNpub by appContainer.sessionManager.activeNpub.collectAsState()
    val calendarEnabled by appContainer.relaySettingsRepository.calendarEnabled.collectAsState()

    // Fetch feature flags once so the Calendar tab reflects `calendar_enabled`.
    LaunchedEffect(Unit) { appContainer.relaySettingsRepository.refresh() }

    var showSwitcher by remember { mutableStateOf(false) }
    var isSwitching by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }

    // Unseen login-security alerts per account — badges in the switcher
    // sheet's per-account drill-in (the active account's count also drives
    // the avatar shield ring via [unseenSecurityCount]).
    val unseenRows by appContainer.securityAlertDao.observeUnseenCountsByOwner()
        .collectAsState(initial = emptyList())
    val unseenSecurityCounts = remember(unseenRows) {
        unseenRows.associate { it.npub to it.unseen }
    }

    fun openSwitcher() {
        // Heal a stale fail-closed pgp_enabled gate: the relay toggle may
        // have flipped since app start (nginx caches the config 5 min).
        appContainer.pgpFeatureGate.maybeRefresh()
        showSwitcher = true
    }
    fun closeSwitcher() { showSwitcher = false }

    fun doSwitch(npub: String) {
        scope.launch {
            isSwitching = true
            try {
                appContainer.switchAccountUseCase.switchTo(npub)
                // Force the home destination (and therefore its ViewModels)
                // to be destroyed and recreated so it picks up the new active
                // npub. Without this, every screen's ViewModel keeps observing
                // the OLD account's data until the process dies.
                onAccountSwitched()
            } catch (e: Throwable) {
                android.util.Log.e("AppNavigationBar", "Switch to $npub failed: ${e.message}", e)
            } finally {
                isSwitching = false
                showSwitcher = false
            }
        }
    }

    // Overflow destinations behind the "More" tab.
    val overflowRoutes = remember {
        setOf("files", "contacts", "storage", "settings")
    }
    val isOverflowSelected = selectedRoute in overflowRoutes

    // Selection styling: color only — each tab tints its icon + label via
    // NavTab; the built-in M3 indicator stays neutralized (there is no
    // replacement background — selection reads from the tint alone).
    val colorScheme = MaterialTheme.colorScheme
    val navItemColors = NavigationBarItemDefaults.colors(
        indicatorColor = Color.Transparent
    )

    // Compact 64dp bar (M3 default is 80dp) to keep screen chrome tight.
    // The height is grown by the bottom system-inset so the gesture-nav area
    // becomes part of the bar (same container color) instead of eating into
    // the fixed 64dp of tab space — under edge-to-edge the M3 default pads
    // the inset INSIDE the given height, which squeezed the tabs into a
    // ~16dp sliver (clipped labels, squashed icons).
    val density = LocalDensity.current
    val gestureInsetDp = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    NavigationBar(modifier = Modifier.height(64.dp + gestureInsetDp)) {
        // ---- Avatar / Profile (account switcher sheet) ----
        NavigationBarItem(
            selected = false,
            onClick = { openSwitcher() },
            icon = {
                NavTab(
                    selected = false,
                    // No label: the 42dp avatar IS the whole column — the same
                    // height as the labeled tabs (24dp icon + 2dp gap + 16dp
                    // label line) — so both columns center identically and the
                    // photo spans icon-top to label-bottom like the web bar.
                    label = null,
                    accent = colorScheme.primary
                ) {
                    ShieldBadgedAvatar(
                        account = activeAccount,
                        unseenSecurityCount = unseenSecurityCount,
                        ringColor = null
                    )
                }
            },
            colors = navItemColors
        )

        // ---- Email ----
        val emailSelected = selectedRoute == "emails/inbox"
        NavigationBarItem(
            selected = emailSelected,
            onClick = { onNavigateEmails() },
            icon = {
                NavTab(
                    selected = emailSelected,
                    label = "Email",
                    accent = colorScheme.primary
                ) {
                    BadgedIcon(count = unreadEmailCount) {
                        Icon(Icons.Default.Email, contentDescription = "Email")
                    }
                }
            },
            colors = navItemColors
        )

        // ---- Calendar (gated on the relay's calendar_enabled flag) ----
        if (calendarEnabled) {
            val calendarSelected = selectedRoute.startsWith("calendar/list")
            NavigationBarItem(
                selected = calendarSelected,
                onClick = { onNavigateCalendar() },
                icon = {
                    NavTab(
                        selected = calendarSelected,
                        label = "Calendar",
                        accent = colorScheme.primary
                    ) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = "Calendar")
                    }
                },
                colors = navItemColors
            )
        }

        // ---- Notes ----
        val notesSelected = selectedRoute == "notes/list"
        NavigationBarItem(
            selected = notesSelected,
            onClick = { onNavigateNotes() },
            icon = {
                NavTab(
                    selected = notesSelected,
                    label = "Notes",
                    accent = colorScheme.primary
                ) {
                    Icon(Icons.Default.Article, contentDescription = "Notes")
                }
            },
            colors = navItemColors
        )

        // ---- More (overflow) ----
        // Storage, Files, Contacts and Settings live behind this button to
        // keep the bar compact. Tapping it opens a popup that expands upward
        // because the anchor sits at the bottom.
        NavigationBarItem(
            selected = isOverflowSelected,
            onClick = { showOverflow = true },
            icon = {
                NavTab(
                    selected = isOverflowSelected,
                    label = "More",
                    accent = colorScheme.primary
                ) {
                    Box {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                        DropdownMenu(
                        expanded = showOverflow,
                        onDismissRequest = { showOverflow = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Storage") },
                            onClick = {
                                showOverflow = false
                                onNavigateStorage()
                            },
                            leadingIcon = { Icon(Icons.Default.Storage, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Files") },
                            onClick = {
                                showOverflow = false
                                onNavigateFiles()
                            },
                            leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Contacts") },
                            onClick = {
                                showOverflow = false
                                onNavigateContacts()
                            },
                            leadingIcon = { Icon(Icons.Default.Contacts, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            onClick = {
                                showOverflow = false
                                onNavigateSettings()
                            },
                            leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) }
                        )
                        }
                    }
                }
            },
            colors = navItemColors
        )
    }

    if (showSwitcher) {
        val pgpEnabled by appContainer.pgpFeatureGate.enabled.collectAsState()
        AccountSwitcherSheet(
            accounts = accounts,
            activeNpub = activeNpub,
            isSwitching = isSwitching,
            unseenSecurityCounts = unseenSecurityCounts,
            pgpEnabled = pgpEnabled,
            onSwitchTo = ::doSwitch,
            onAddAccount = {
                closeSwitcher()
                onNavigateToAddAccount()
            },
            onEditAccount = {
                closeSwitcher()
                onEditAccount()
            },
            onOpenBunkerConnections = { npub ->
                closeSwitcher()
                onOpenBunkerConnections(npub)
            },
            onOpenSecurityAlerts = { npub ->
                closeSwitcher()
                onOpenSecurityAlerts(npub)
            },
            onOpenBackup = { npub ->
                closeSwitcher()
                onOpenBackup(npub)
            },
            onOpenPgpSettings = {
                closeSwitcher()
                onOpenPgpSettings()
            },
            onDismiss = ::closeSwitcher
        )
    }
}

@Composable
private fun AvatarIcon(account: Account?) {
    val context = LocalContext.current
    if (account?.picture != null) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(account.picture).crossfade(true).build(),
            contentDescription = "Profile",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(42.dp)
                .clip(AvatarShape)
        )
    } else {
        // Identicon-style fallback so the avatar is visible even before the
        // kind-0 profile picture has loaded, or for accounts that have none.
        val initials = (account?.shortLabel ?: "?").firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(AvatarShape)
                .background(account.avatarColor()),
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

/**
 * The avatar tab, wrapped with indicators depending on state:
 *
 * - While unseen login-security alerts exist: a hard 2dp [Amber] ring
 *   (solid stroke — deliberately no glow) plus a small shield chip at the
 *   bottom-end corner. Both appear/disappear together off the same reactive
 *   unseen-count; tapping the avatar opens the switcher sheet, whose
 *   Security row carries the actual count badge.
 * - Otherwise, when a ring color is supplied via [ringColor]: the same thin
 *   ring construction in the accent color — a photo can't be tinted like
 *   the vector icons. The security ring takes precedence, so the two never
 *   stack.
 *
 * The photo is a 42dp rounded thumbnail that fills the tab's whole content
 * column — exactly the height of the labeled tabs (24dp icon + 2dp gap +
 * 16dp label line, `labelMedium`'s default line box) — so it spans icon-top
 * to label-bottom like the web bar and never overhangs the row. The
 * security ring is stroked on the thumbnail's edge (reading as a ~44dp
 * outline) so the photo never shrinks or shifts under it.
 */
@Composable
private fun ShieldBadgedAvatar(
    account: Account?,
    unseenSecurityCount: Int,
    ringColor: Color? = null
) {
    val securityActive = unseenSecurityCount > 0
    val effectiveRing = if (securityActive) Amber else ringColor
    if (effectiveRing == null) {
        AvatarIcon(account = account)
        return
    }
    Box(
        modifier = Modifier
            .size(42.dp)
            .border(
                width = 2.dp,
                color = effectiveRing,
                shape = AvatarShape
            )
    ) {
        AvatarIcon(account = account)
        if (securityActive) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(13.dp)
                    .background(Amber, androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = "$unseenSecurityCount unseen security alerts",
                    tint = Color(0xFF0D0D1A),
                    modifier = Modifier.size(9.dp)
                )
            }
        }
    }
}

/**
 * A bottom-nav tab: icon content over a label. Selection is conveyed by
 * tint alone — icon + label take [accent] when selected and
 * [idleContentColor] otherwise; there is deliberately no background
 * indicator (the M3 default is a fixed 64dp pill behind the icon only — it
 * bleeds past the avatar and never touches the label — and can't be
 * resized via the public API, hence it stays neutralized).
 *
 * Icon tint is driven by [LocalContentColor] so vector icons follow the
 * accent without each call site setting `tint` explicitly. The avatar tab
 * is the exception: a photo can't be tinted and it carries no label, so its
 * state cues are the ring/chip drawn by [ShieldBadgedAvatar], not tint.
 *
 * @param label optional text under the icon; null renders the icon content
 * alone (the avatar tab passes null — its thumbnail already fills the full
 * column height).
 * @param accent selected-state icon + label color (theme primary).
 * @param idleContentColor unselected icon + label color.
 */
@Composable
private fun NavTab(
    selected: Boolean,
    label: String?,
    accent: Color,
    idleContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    content: @Composable () -> Unit
) {
    val contentColor = if (selected) accent else idleContentColor
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor
            )
        }
    }
}

@Composable
private fun BadgedIcon(count: Int, fallback: @Composable () -> Unit) {
    if (count > 0) {
        BadgedBox(
            badge = {
                Badge {
                    val display = if (count > 99) "99+" else count.toString()
                    Text(text = display)
                }
            }
        ) { fallback() }
    } else {
        fallback()
    }
}

private fun Account?.avatarColor(): Color {
    if (this == null) return Color(0xFF6B7A85)
    val palette = listOf(
        Color(0xFF5E6BD4), Color(0xFFC46B5E), Color(0xFF5EA872),
        Color(0xFFB59346), Color(0xFF7A5EB5), Color(0xFF5EA5B5),
        Color(0xFFB55E84), Color(0xFF6B7A85)
    )
    val hash = npub.hashCode()
    return palette[(hash and Int.MAX_VALUE) % palette.size]
}
