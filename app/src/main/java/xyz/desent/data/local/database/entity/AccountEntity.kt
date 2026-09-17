package xyz.desent.data.local.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per Nostr account that has been logged into on this device.
 *
 * The set of rows is the source of truth for the account switcher; the
 * "active" account is the npub stored under
 * [xyz.desent.data.local.preferences.PreferencesManager.npubKey].
 */
@Entity(
    tableName = "accounts",
    indices = [Index(value = ["lastActiveAt"])]
)
data class AccountEntity(
    @PrimaryKey val npub: String,
    val displayName: String?,
    val picture: String?,
    val nip05: String?,
    val requiresBiometrics: Boolean = false,
    val lastActiveAt: Long = 0L,
    val addedAt: Long = System.currentTimeMillis(),
    /**
     * Local part of the client-custodied (username & password) login that
     * provisioned this account on this device, or null for key-import
     * accounts. Drives the Settings "change password" entry point.
     */
    val custodialUsername: String? = null,
    /**
     * Cached primary `<local>@desent.xyz` address from `GET /api/me`
     * (refreshed at launch/login/switch). Distinct from [nip05]: the
     * registered address is authoritative even when the kind-0 profile
     * carries a different/external nip05.
     */
    val primaryAddress: String? = null
)
