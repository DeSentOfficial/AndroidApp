package xyz.desent.presentation.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.desent.R
import xyz.desent.presentation.theme.Spacing
import xyz.desent.presentation.ui.components.AccountBarTitle
import xyz.desent.presentation.ui.components.DesentLogoMark
import xyz.desent.presentation.ui.settings.component.SettingsNavRow

/**
 * The Settings hub (hub-and-spoke): the App tab is a directory of drill-downs —
 * one row per group, grouped into Common / Data & access / Extensions cards —
 * and the About tab is unchanged. Every row opens a spoke sub-screen in this
 * package; heavy groups (mailbox rules, app lock, spam policy) no longer
 * sprawl down a single page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToAppearance: () -> Unit = {},
    onNavigateToPrivacy: () -> Unit = {},
    onNavigateToNotifications: () -> Unit = {},
    onNavigateToMail: () -> Unit = {},
    onNavigateToAntiSpam: () -> Unit = {},
    onNavigateToRollKey: () -> Unit = {},
    onNavigateToBackup: () -> Unit = {},
    onNavigateToInvites: () -> Unit = {},
    onNavigateToAgents: () -> Unit = {},
    onNavigateToMarkdownFiles: () -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        TopAppBar(
            title = { AccountBarTitle() },
            navigationIcon = {
                DesentLogoMark()
            }
        )

        val pagerState = rememberPagerState(initialPage = 0) { SettingsTab.entries.size }
        val scope = rememberCoroutineScope()

        TabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth()
        ) {
            SettingsTab.entries.forEachIndexed { index, tab ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text = { Text(tab.label) }
                )
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                when (SettingsTab.entries[page]) {
                    SettingsTab.APP -> {
                        // ---- Common ----
                        HubCard {
                            SettingsNavRow(
                                icon = Icons.Default.Palette,
                                title = "Appearance",
                                subtitle = "System, light or dark theme",
                                onClick = onNavigateToAppearance
                            )
                            SettingsNavRow(
                                icon = Icons.Default.Lock,
                                title = "Security & privacy",
                                subtitle = "PIN, biometrics, sign-in alerts",
                                onClick = onNavigateToPrivacy
                            )
                            SettingsNavRow(
                                icon = Icons.Default.Notifications,
                                title = "Notifications",
                                subtitle = "Email, in-app alerts",
                                onClick = onNavigateToNotifications
                            )
                            SettingsNavRow(
                                icon = Icons.Default.Email,
                                title = "Mail",
                                subtitle = "PGP keys, mailbox rules, sender cache",
                                onClick = onNavigateToMail
                            )
                            SettingsNavRow(
                                icon = Icons.Default.FilterAlt,
                                title = "Anti-spam",
                                subtitle = "Filter policy & remote images",
                                onClick = onNavigateToAntiSpam
                            )
                        }

                        // ---- Data & access ----
                        // Screens that used to live behind bare deep links or
                        // inbox menus get a real entry point here.
                        HubCard {
                            SettingsNavRow(
                                icon = Icons.Default.Key,
                                title = "Key management",
                                subtitle = "Rotate your Nostr signing key",
                                onClick = onNavigateToRollKey
                            )
                            SettingsNavRow(
                                icon = Icons.Default.Backup,
                                title = "Backup & restore",
                                subtitle = "Encrypted backup of your DeSent data",
                                onClick = onNavigateToBackup
                            )
                            SettingsNavRow(
                                icon = Icons.Default.Redeem,
                                title = "Invite codes",
                                subtitle = "Create and manage invite codes",
                                onClick = onNavigateToInvites
                            )
                        }

                        // ---- Extensions ----
                        // AI Agents' screen fails closed on the tier-info
                        // flag, so no client-side gate is needed on the row.
                        // (Relay mirroring lives in the Mail spoke now.)
                        HubCard {
                            SettingsNavRow(
                                icon = Icons.Default.SmartToy,
                                title = "AI Agents",
                                subtitle = "Your OpenClaw-style agents",
                                onClick = onNavigateToAgents
                            )
                            SettingsNavRow(
                                icon = Icons.Default.Description,
                                title = "Markdown files",
                                subtitle = "Open .md files as encrypted notes",
                                onClick = onNavigateToMarkdownFiles
                            )
                        }
                    }

                    SettingsTab.ABOUT -> {
                        // Branding: app identity, maker, version.
                        AboutSection()
                    }
                }
            }
        }
    }
}

/** One grouped card of hub directory rows. */
@Composable
private fun HubCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            content()
        }
    }
}

/** The top-level settings tabs, one per group of related sections. */
private enum class SettingsTab(val label: String) {
    APP("App"),
    ABOUT("About")
}

/**
 * Branding: app identity, maker, version and website.
 * Reads versionName from PackageManager so it stays in sync with build.gradle.
 */
@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Text(
            text = stringResource(R.string.settings_about),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_desent_logo),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(56.dp)
                )

                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = stringResource(R.string.about_made_by),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider()

                AboutRow(
                    label = stringResource(R.string.about_version),
                    value = versionName ?: ""
                )

                HorizontalDivider()

                Text(
                    text = stringResource(R.string.about_website),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://desent.xyz"))
                            )
                        } catch (_: ActivityNotFoundException) {
                            // No browser installed; nothing sensible to do.
                        }
                    }
                )

                Text(
                    text = stringResource(R.string.about_copyright),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
