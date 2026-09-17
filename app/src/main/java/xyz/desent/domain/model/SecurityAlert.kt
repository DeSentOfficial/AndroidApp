package xyz.desent.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A login-security alert received from the relay
 * (refs/FromServer/ANDROID_SECURITY_ALERTS.md).
 *
 * The relay gift-wraps a kind-1010 rumor with `direction: "security"` whenever
 * someone authenticates as the user (WS NIP-42, HTTP NIP-98 or the admin
 * panel). This is detection, not prevention — see the protocol doc §1. The
 * alert arrives on the existing `{kinds:[1059], #p:[me]}` subscription; the
 * seal is always signed by the relay's npub, which the processor verifies
 * before storing.
 */
data class SecurityAlert(
    /** Gift-wrap event id — the dedup + mark-seen key. */
    val eventId: String,
    /** Owning account (the `p` tag of the wrap). */
    val ownerNpub: String,
    /** e.g. "New sign-in to your account". */
    val subject: String,
    /** Auth surface: "ws" | "http" | "admin". */
    val surface: String,
    /** Authoritative relay-clock timestamp, "YYYY-MM-DD HH:MM UTC". */
    val time: String,
    /** Client IP as the relay saw it; null when unknown. */
    val ip: String? = null,
    /** "City, CC"; null when GeoLite2 is not configured server-side. */
    val geo: String? = null,
    /** Raw User-Agent (server caps at 500 chars); null when unknown. */
    val ua: String? = null,
    /** First 16 hex chars of the device fingerprint — stable per device+network. */
    val device: String = "",
    /** Plain-text human summary ("When/Where/Device/Via" + guidance). */
    val body: String = "",
    /** Wall-clock millis when the wrap was stored locally. */
    val receivedAt: Long = 0L,
    /** False until the user opens the alert (or the notification fires). */
    val isSeen: Boolean = false
)

/**
 * The `security_alerts` preference on the kind-30079 user-settings event
 * (refs/FromServer/USER_SETTINGS_PROTOCOL.md § Content schema).
 */
@Serializable
enum class SecurityAlertMode(val wireValue: String) {
    /** Never alert. */
    OFF("off"),

    /** Alert when an unseen device fingerprint authenticates (server default). */
    NEW_DEVICE("new_device"),

    /** Alert on every (deduped) sign-in. */
    ALWAYS("always");

    companion object {
        /** Absent field in a stored event = the server default (`new_device`). */
        val DEFAULT: SecurityAlertMode = NEW_DEVICE

        fun fromWire(value: String?): SecurityAlertMode =
            entries.firstOrNull { it.wireValue == value } ?: DEFAULT
    }
}

/**
 * The user's security configuration slice — its own configuration, kept
 * separate from the kind-35050 MailboxConfig and the 30078 spam-settings
 * namespace. Publishes as a **partial** kind-30079 payload
 * (`{"security_alerts": "<mode>"}` only) so auto-purge fields are never
 * touched (USER_SETTINGS_PROTOCOL.md § Partial updates).
 */
data class SecurityConfig(
    val alertMode: SecurityAlertMode = SecurityAlertMode.DEFAULT,
    /**
     * `pgp_auto_encrypt` (ANDROID_PGP.md §5): client-only preference —
     * pre-check the compose lock when the recipient has a discoverable key.
     */
    val pgpAutoEncrypt: Boolean = false,
    /**
     * `dm_fanout` (ANDROID_DM_FANOUT.md §2): premium opt-in — mirrors
     * inbound mail + delivery receipts to the relays of the user's NIP-65
     * list. Also drives the automatic backup fetch from those relays.
     */
    val dmFanout: Boolean = false
)

/**
 * Wire payload of the kind-30079 user-settings event. The schema is CLOSED
 * server-side (unknown fields reject the publish), so this mirrors exactly
 * the documented fields. Security publishes encode ONLY `security_alerts`
 * (null fields are omitted with `encodeDefaults = false`) — partial-update
 * semantics guarantee the other fields stay untouched relay-side.
 */
@Serializable
data class UserSettingsPayload(
    @SerialName("security_alerts")
    val securityAlerts: String? = null,
    @SerialName("wot_enabled")
    val wotEnabled: Boolean? = null,
    @SerialName("wot_max_hops")
    val wotMaxHops: Int? = null,
    @SerialName("pgp_auto_encrypt")
    val pgpAutoEncrypt: Boolean? = null,
    @SerialName("dm_fanout")
    val dmFanout: Boolean? = null
)
