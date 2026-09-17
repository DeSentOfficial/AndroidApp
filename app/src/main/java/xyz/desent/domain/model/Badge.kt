package xyz.desent.domain.model

/**
 * NIP-58 badge domain models (refs/FROM_email.desent.xyz/BADGES_PROTOCOL.md).
 *
 * A badge has a relay-authored [BadgeDefinition] (kind 30009) and, per
 * awardee, a relay-authored [BadgeAward] (kind 8). The user pins a subset
 * of their awards on their own profile via a kind 30008 event.
 */

/** How a badge's art is rendered (BADGES_PROTOCOL.md §Art modes). */
enum class BadgeArtMode {
    /** DeSent extension: render a named glyph locally, no network fetch. */
    ICON,

    /** Fetch `image` (or `thumb` in dense contexts) over HTTPS. */
    IMAGE
}

data class BadgeDefinition(
    val slug: String,
    val name: String,
    val description: String? = null,
    val imageUrl: String? = null,
    val thumbUrl: String? = null,
    /** Material Symbols glyph name (icon mode). */
    val iconName: String? = null,
    /** `#rrggbb` fill for icon mode; null falls back to the app accent. */
    val color: String? = null
) {
    val artMode: BadgeArtMode
        get() = if (iconName != null) BadgeArtMode.ICON else BadgeArtMode.IMAGE
}

data class BadgeAward(
    /** Kind 8 event id — the `e` value in a kind 30008 pin list. */
    val eventId: String,
    val awardeeNpub: String,
    val slug: String,
    /** Verbatim `a` tag of the award: `30009:<relay npub>:<slug>`. */
    val definitionAddress: String,
    /** Kind 8 `created_at` (seconds). */
    val awardedAt: Long
)

/**
 * An earned badge joined with its definition and current pin state — the
 * shape the profile editor and badge sheet render.
 */
data class EarnedBadge(
    val definition: BadgeDefinition,
    val award: BadgeAward,
    val isPinned: Boolean
)

/**
 * A relay-sealed kind-1010 `direction: "badge"` award notice
 * (ANDROID_BADGES.md §7). Persisted in `badge_notices` (the notifications
 * tray); the arrival flow fires only on the first insert of a wrap id.
 */
data class BadgeAwardNotice(
    val eventId: String,
    val ownerNpub: String,
    val slug: String,
    val subject: String,
    val body: String
)
