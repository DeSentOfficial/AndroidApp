package xyz.desent.presentation.ui.accounts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import xyz.desent.R
import xyz.desent.domain.model.Account
import xyz.desent.presentation.theme.Amber
import xyz.desent.presentation.theme.Spacing
import xyz.desent.presentation.ui.components.AvatarShape

/**
 * Modal bottom sheet that is the accounts-and-access surface, with two levels
 * rendered inside the same small popup:
 *
 * Level 1 lists every saved account (active one marked) plus "Add another
 * account" ([onAddAccount]). Selecting an account drills into a per-account
 * section (level 2) offering that account's profile editor ([onEditAccount],
 * active account only), bunker connections ([onOpenBunkerConnections]),
 * security alerts ([onOpenSecurityAlerts]) and account backup
 * ([onOpenBackup]) — each scoped to the tapped account, whether or not it is
 * the active one. When the tapped account isn't active, a "Switch to this
 * account" row invokes [onSwitchTo]; the actual switch work is done by
 * [xyz.desent.domain.usecase.SwitchAccountUseCase] via the screen's ViewModel —
 * this composable is purely presentational.
 *
 * Removal is intentionally NOT exposed here (per the agreed UX: switching only;
 * logout/removal lives on the Account Details screen reached via
 * [onEditAccount]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSwitcherSheet(
    accounts: List<Account>,
    activeNpub: String?,
    isSwitching: Boolean,
    /** Unseen login-security alerts per npub — badge on the drill-in Security row. */
    unseenSecurityCounts: Map<String, Int> = emptyMap(),
    /** Relay's pgp_enabled gate — the PGP row is hidden (fail-closed) when off. */
    pgpEnabled: Boolean = false,
    onSwitchTo: (npub: String) -> Unit,
    onAddAccount: () -> Unit,
    /** Opens the profile editor — active account only (see [AccountDetailLevel]). */
    onEditAccount: () -> Unit = {},
    /** Opens bunker connections scoped to [npub] (NIP-46 remote-signing sessions). */
    onOpenBunkerConnections: (npub: String) -> Unit = {},
    onOpenSecurityAlerts: (npub: String) -> Unit,
    onOpenBackup: (npub: String) -> Unit,
    /** PGP key management — active account only (see [AccountDetailLevel]). */
    onOpenPgpSettings: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedNpub by remember { mutableStateOf<String?>(null) }

    // System back pops the drill-in level first; only when at the list level
    // does the default (sheet dismiss) behavior apply.
    BackHandler(enabled = selectedNpub != null) { selectedNpub = null }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
        ) {
            if (isSwitching) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.lg),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            text = "Switching account…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                val selected = selectedNpub?.let { npub -> accounts.firstOrNull { it.npub == npub } }
                if (selected == null) {
                    AccountsListLevel(
                        accounts = accounts,
                        activeNpub = activeNpub,
                        onSelectAccount = { selectedNpub = it },
                        onAddAccount = onAddAccount
                    )
                } else {
                    AccountDetailLevel(
                        account = selected,
                        isActive = selected.npub == activeNpub,
                        unseenCount = unseenSecurityCounts[selected.npub] ?: 0,
                        pgpEnabled = pgpEnabled,
                        onBack = { selectedNpub = null },
                        onEditAccount = onEditAccount,
                        onOpenBunkerConnections = { onOpenBunkerConnections(selected.npub) },
                        onOpenSecurityAlerts = { onOpenSecurityAlerts(selected.npub) },
                        onOpenBackup = { onOpenBackup(selected.npub) },
                        onOpenPgpSettings = onOpenPgpSettings,
                        onSwitchTo = { onSwitchTo(selected.npub) }
                    )
                }
            }
            Spacer(Modifier.height(Spacing.md))
        }
    }
}

// ---------------- Level 1: account list ----------------

@Composable
private fun AccountsListLevel(
    accounts: List<Account>,
    activeNpub: String?,
    onSelectAccount: (npub: String) -> Unit,
    onAddAccount: () -> Unit
) {
    Text(
        text = "Accounts",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(Spacing.sm))

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        items(accounts, key = { it.npub }) { account ->
            AccountRow(
                account = account,
                isActive = account.npub == activeNpub,
                onClick = { onSelectAccount(account.npub) }
            )
        }

        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm))
            AddAccountRow(onClick = onAddAccount)
        }
    }
}

@Composable
private fun AccountRow(
    account: Account,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.sm, horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AccountAvatar(account = account, size = 44.dp)

        Spacer(Modifier.width(Spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = account.shortLabel,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = account.desentAddress ?: "No address",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (isActive) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Active account",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun AddAccountRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.sm, horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.PersonAdd,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(Spacing.md))
        Text(
            text = "Add another account",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
    }
}

// ---------------- Level 2: per-account section ----------------

/**
 * Drill-in section for one account: the account's access surfaces plus, for a
 * non-active account, an explicit "Switch to this account" row.
 */
@Composable
private fun AccountDetailLevel(
    account: Account,
    isActive: Boolean,
    unseenCount: Int,
    pgpEnabled: Boolean,
    onBack: () -> Unit,
    onEditAccount: () -> Unit,
    onOpenBunkerConnections: () -> Unit,
    onOpenSecurityAlerts: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenPgpSettings: () -> Unit,
    onSwitchTo: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back to accounts",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        AccountAvatar(account = account, size = 36.dp)
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = account.shortLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = account.desentAddress ?: "No address",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isActive) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = stringResource(R.string.account_active_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }

    Spacer(Modifier.height(Spacing.sm))
    HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm))

    // Profile editor — active account only: ProfileEditScreen edits the
    // active identity's kind-0 metadata.
    if (isActive) {
        AccountOptionRow(
            icon = Icons.Default.Edit,
            iconTint = MaterialTheme.colorScheme.primary,
            title = stringResource(R.string.account_edit_row),
            subtitle = stringResource(R.string.account_edit_subtitle),
            onClick = onEditAccount
        )
    }
    AccountOptionRow(
        icon = Icons.Default.Key,
        iconTint = MaterialTheme.colorScheme.primary,
        title = stringResource(R.string.account_bunker_connections),
        subtitle = stringResource(R.string.account_bunker_connections_subtitle),
        onClick = onOpenBunkerConnections
    )
    AccountOptionRow(
        icon = Icons.Default.Security,
        iconTint = Amber,
        title = stringResource(R.string.security_alerts_title),
        subtitle = stringResource(R.string.security_alerts_subtitle),
        unseenCount = unseenCount,
        onClick = onOpenSecurityAlerts
    )
    AccountOptionRow(
        icon = Icons.Default.Save,
        iconTint = MaterialTheme.colorScheme.primary,
        title = stringResource(R.string.account_backup_row),
        subtitle = stringResource(R.string.account_backup_subtitle),
        onClick = onOpenBackup
    )
    // PGP key management (ANDROID_PGP.md §2): active account only — the key
    // manager resolves the active identity's 30078 `desent:pgp` namespace,
    // so drilling a non-active account would manage the wrong key. Hidden
    // (fail-closed) while the relay's pgp_enabled gate is off.
    if (isActive && pgpEnabled) {
        AccountOptionRow(
            icon = Icons.Default.Key,
            iconTint = MaterialTheme.colorScheme.primary,
            title = stringResource(R.string.pgp_key_row_title),
            subtitle = stringResource(R.string.pgp_key_row_subtitle),
            onClick = onOpenPgpSettings
        )
    }

    if (!isActive) {
        HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm))
        SwitchAccountRow(onClick = onSwitchTo)
    }
}

@Composable
private fun AccountOptionRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    unseenCount: Int = 0
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.sm, horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (unseenCount > 0) {
            BadgedBox(
                badge = {
                    Badge(containerColor = Amber) {
                        val display = if (unseenCount > 99) "99+" else unseenCount.toString()
                        Text(display, color = Color(0xFF0D0D1A))
                    }
                }
            ) {}
        }
    }
}

@Composable
private fun SwitchAccountRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.sm, horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.SwapHoriz,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(Spacing.md))
        Text(
            text = stringResource(R.string.switch_to_this_account),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
    }
}

// ---------------- shared ----------------

@Composable
private fun AccountAvatar(account: Account, size: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    val picture = account.picture
    if (picture != null) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(picture).crossfade(true).build(),
            contentDescription = account.shortLabel,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(AvatarShape)
        )
    } else {
        // Identicon fallback: a colored tile with the first letter of the
        // display name (or the first char of the npub).
        val initials = account.shortLabel.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        Box(
            modifier = Modifier
                .size(size)
                .clip(AvatarShape)
                .background(colorForNpub(account.npub)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initials,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** Deterministic color derived from the npub so each account looks distinct. */
private fun colorForNpub(npub: String): Color {
    val palette = listOf(
        Color(0xFF5E6BD4), Color(0xFFC46B5E), Color(0xFF5EA872),
        Color(0xFFB59346), Color(0xFF7A5EB5), Color(0xFF5EA5B5),
        Color(0xFFB55E84), Color(0xFF6B7A85)
    )
    val hash = npub.hashCode()
    return palette[(hash and Int.MAX_VALUE) % palette.size]
}
