package xyz.desent.data.nostr

/**
 * The app's single catalog of Nostr event kinds.
 *
 * The `nostr-java` library exposes [nostr.base.Kinds] for many (but not all)
 * kinds this app processes. Historically the codebase mixed library constants
 * with bare integer literals (e.g. `1059`, `30315`, `10002`), making it hard
 * to see which kinds are handled and why.
 *
 * This object is the authoritative discovery point for every kind the app
 * reads or writes. Group each new kind under its NIP and keep the comment
 * short but specific. Prefer these constants over raw literals and over the
 * library constants in app code so there is one place to audit.
 *
 * Reference: https://github.com/nostr-protocol/nips
 */
object NostrKinds {

    // ==================== NIP-01: Basic protocol ====================
    /** User profile metadata (replaceable). */
    const val SET_METADATA = 0
    /** Short text note. */
    const val TEXT_NOTE = 1
    /** Recommend relay (deprecated by NIP-65). */
    const val RECOMMEND_SERVER = 2

    // ==================== NIP-02: Contact list ====================
    /** Following / contact list (replaceable). */
    const val CONTACT_LIST = 3

    // ==================== NIP-09: Deletion ====================
    const val DELETION = 5

    // ==================== NIP-58: Badges ====================
    // See refs/FROM_email.desent.xyz/BADGES_PROTOCOL.md. All three kinds stay
    // internal to the DeSent relay (never broadcast elsewhere); reads require
    // NIP-42 AUTH. Kinds 8 and 30009 are authored by the relay npub only.
    /** Badge award (relay-authored, `p` = awardee, `a` = definition address). */
    const val BADGE_AWARD = 8
    /** Profile badges pin list (user-authored, `d = "profile_badges"`). */
    const val PROFILE_BADGES = 30008
    /** Badge definition (relay-authored, `d` = badge slug). */
    const val BADGE_DEFINITION = 30009

    // ==================== NIP-59: Private messaging envelope ====================
    /** NIP-59 seal (encrypted to recipient). */
    const val SEAL = 13
    /**
     * Wrapped rumor kind (inside the seal). Originally NIP-17 chat; this app
     * now uses it as the envelope for bridged features — NIP-46 bunker
     * traffic, calendar shares and legacy email-tagged messages — all
     * disambiguated by tag set.
     */
    const val PRIVATE_DIRECT_MESSAGE = 14
    /** NIP-59 gift wrap (wraps a seal). */
    const val GIFT_WRAP = 1059

    // ==================== NIP-46: Remote signing ====================
    /**
     * Standard NIP-46 request/response event. Raw transport for external
     * remote-signing clients (Amethyst/NDK, Damus, iris, nsec.app); the
     * DeSent-internal variant rides gift-wrapped kind-1059 rumors tagged
     * `["bridge","nip46"]` instead (refs/NIP46_BUNKER_CLIENT.md §16).
     */
    const val NIP46_REQUEST = 24133

    // ==================== NIP-XX (Email over Nostr) ====================
    // See refs/FromServer/NIP-EMAIL.md. Email rides a dedicated rumor kind so
    // standard NIP-17 clients ignore it instead of misrendering it as a DM.
    /** Email Message rumor (inside the seal; unsigned). */
    const val EMAIL_MESSAGE = 1010
    /** Mailbox Configuration (addressable, NIP-78-style; `d` = user pubkey). */
    const val MAILBOX_CONFIGURATION = 35050

    // ==================== NIP-51: Lists ====================
    /** Public mute list. */
    const val MUTE_LIST = 10000
    /** Public bookmark list. */
    const val BOOKMARK_LIST = 10003
    /** Private categorized-people list (content NIP-44 encrypted). */
    const val CATEGORIZED_PEOPLE = 10004

    // ==================== NIP-65: Relay list ====================
    /**
     * User's relay list (replaceable; `r` tags ± `read`/`write` markers).
     * DeSent uses it as the relay-mirroring target list — published to and
     * read back from THIS relay only (ANDROID_DM_FANOUT.md §1).
     */
    const val RELAY_LIST = 10002

    // ==================== NIP-78: Application-specific data ====================
    const val APPLICATION_SPECIFIC_DATA = 30078

    // ==================== DeSent user settings (custom, sibling of 30078) ====================
    /**
     * User settings (parameterized replaceable, `d = "desent_user_settings"`).
     * Plaintext JSON the relay reads to update the user's `email_settings` row
     * (auto-purge + login-security alert mode). Owner-scoped reads, quota-exempt
     * publishes. See refs/FromServer/USER_SETTINGS_PROTOCOL.md.
     */
    const val USER_SETTINGS = 30079

    // ==================== NIP-52: Calendar ====================
    const val CALENDAR_DATE_BASED_EVENT = 31922
    const val CALENDAR_TIME_BASED_EVENT = 31923
    const val CALENDAR_EVENT = 31924
    const val CALENDAR_RSVP_EVENT = 31925

    // ==================== NIP-42: Client auth ====================
    const val CLIENT_AUTH = 22242
}
