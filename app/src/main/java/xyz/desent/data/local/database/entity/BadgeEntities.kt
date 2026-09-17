package xyz.desent.data.local.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A cached NIP-58 badge definition (kind 30009, relay-authored; see
 * refs/FROM_email.desent.xyz/BADGES_PROTOCOL.md). One row per badge slug.
 * Retirement (empty-content 30009 tombstone) deletes the row.
 */
@Entity(
    tableName = "badge_definitions",
    indices = [Index("eventId")]
)
data class BadgeDefinitionEntity(
    /** Badge slug — the kind 30009 `d` tag; immutable identity of the badge. */
    @PrimaryKey
    val slug: String,
    /** Kind 30009 event id (kind 5 deletion matching). */
    val eventId: String,
    val name: String,
    val description: String? = null,
    /** Absolute HTTPS URL (image mode). */
    val imageUrl: String? = null,
    /** Smaller variant for dense contexts (image mode). */
    val thumbUrl: String? = null,
    /** Material Symbols glyph name (icon mode). */
    val iconName: String? = null,
    /** `#rrggbb` fill (icon mode). */
    val color: String? = null,
    /** Kind 30009 `created_at` (seconds) — LWW staleness check. */
    val updatedAt: Long = 0L
)

/**
 * A cached NIP-58 badge award (kind 8, relay-authored). Append-only until
 * revoked — revocation is a kind 5 deletion of the event, which deletes the
 * row plus any pins referencing it. One award per (badge, user), ever.
 */
@Entity(
    tableName = "badge_awards",
    indices = [Index("awardeeNpub"), Index("slug")]
)
data class BadgeAwardEntity(
    /** Kind 8 event id — the `e` value in kind 30008 pin lists. */
    @PrimaryKey
    val eventId: String,
    val awardeeNpub: String,
    val slug: String,
    /** Verbatim award `a` tag: `30009:<relay npub>:<slug>`. */
    val definitionAddress: String,
    /** Kind 8 `created_at` (seconds). */
    val awardedAt: Long = 0L
)

/**
 * One entry of a user's kind 30008 pin list (their displayed badges, in
 * order). Replaced wholesale on every 30008 publish/echo; emptied by the
 * empty-content tombstone.
 */
@Entity(
    tableName = "badge_pins",
    primaryKeys = ["ownerNpub", "awardEventId"],
    indices = [Index("ownerNpub"), Index("awardEventId")]
)
data class BadgePinEntity(
    val ownerNpub: String,
    val awardEventId: String,
    val slug: String,
    /** Position within the ordered pin list. */
    val position: Int
)

/**
 * A persisted badge-award notice (relay-sealed kind-1010 `direction:
 * "badge"` gift wrap; refs/FromServer/ANDROID_BADGES.md §7). Mirrors the
 * `security_alerts` pattern: IGNORE-insert dedups by gift-wrap event id so
 * the OS notification fires exactly once per award — re-downloaded 30-day
 * relay backlog on login stays stored-but-silent.
 */
@Entity(
    tableName = "badge_notices",
    indices = [Index("ownerNpub"), Index("receivedAt")]
)
data class BadgeNoticeEntity(
    /** Gift-wrap event id — dedup key (insert IGNORE) + mark-seen key. */
    @PrimaryKey
    val eventId: String,
    val ownerNpub: String,
    /** Badge slug from the `["badge", slug]` tag; blank when absent. */
    val slug: String,
    val subject: String,
    val body: String,
    val receivedAt: Long = 0L,
    val isSeen: Boolean = false
)
