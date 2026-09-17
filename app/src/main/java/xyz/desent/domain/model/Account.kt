package xyz.desent.domain.model

/**
 * A locally-stored Nostr account that the user has logged into on this device.
 *
 * Multiple accounts can be saved at once; exactly one is "active" at a time
 * (tracked by [xyz.desent.data.local.preferences.PreferencesManager.npubKey]).
 */
data class Account(
    val npub: String,
    val displayName: String?,
    val picture: String?,
    val nip05: String?,
    val requiresBiometrics: Boolean = false,
    val lastActiveAt: Long,
    val addedAt: Long,
    /**
     * Username of the custodial (password) login that provisioned this
     * account on this device, or null for key-import accounts. Non-null
     * enables the Settings "change password" entry point.
     */
    val custodialUsername: String? = null,
    /**
     * Registered primary `<local>@desent.xyz` address from `GET /api/me`
     * (cached in Room, refreshed at launch/login/switch). Authoritative for
     * display; never shadowed by the kind-0 nip05.
     */
    val primaryAddress: String? = null
) {
    /**
     * Short label suitable for compact UI (avatar alt text, switcher rows).
     * Prefers display name, then nip05 local-part, then truncated npub.
     */
    val shortLabel: String
        get() {
            displayName?.takeIf { it.isNotBlank() }?.let { return it }
            nip05?.substringBefore('@')?.takeIf { it.isNotBlank() }?.let { return it }
            return npub.take(12) + "…"
        }

    val desentAddress: String?
        get() = primaryAddress?.takeIf { it.isNotBlank() }
            ?: nip05?.takeIf { it.endsWith("@desent.xyz", ignoreCase = true) }
            ?: custodialUsername?.takeIf { it.isNotBlank() }?.let { "$it@desent.xyz" }
}
