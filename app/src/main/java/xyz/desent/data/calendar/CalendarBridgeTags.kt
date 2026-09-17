package xyz.desent.data.calendar

/**
 * Rumor tag values for calendar gift-wrap traffic
 * (refs/CALENDAR_PROTOCOL.md §Sharing, §RSVP). These ride on the standard
 * NIP-17 three-layer construction (rumor → seal → 1059 gift wrap); the
 * `["bridge","calendar"]` tag lets the recipient route the unwrapped rumor to
 * the calendar module instead of the email bridge or chat handler.
 */
object CalendarBridgeTags {
    const val BRIDGE = "calendar"

    const val TYPE_SHARE = "share"
    const val TYPE_RSVP = "rsvp"

    const val KEY_CALENDAR_KIND = "calendar_kind"
    const val KEY_CALENDAR_D = "calendar_d"
    const val KEY_SHARE_ROLE = "share_role"
    const val KEY_SHARES_VERSION = "shares_version"
    const val SHARES_VERSION = "1"

    /** Recommended NIP-40 TTL for an uncollected calendar share wrap. */
    const val SHARE_WRAP_TTL_SECONDS = 30L * 24 * 60 * 60
}
