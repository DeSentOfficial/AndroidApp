package xyz.desent.data.wearsync

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Shared phone↔watch sync contract for the DeSent Wear companion.
 *
 * The phone pushes a small config payload (currently: theme mode) to the
 * watch as a DataItem; the watch may request a fresh push over the message
 * client. This is the transport future watch features (email, calendar) can
 * piggyback on by extending [WearConfig].
 */
object WearSyncPaths {
    /** DataItem path carrying the serialized [WearConfig] payload. */
    const val CONFIG_PATH = "/desent/config"

    /** Message path (watch → phone) requesting a fresh config push. */
    const val REQUEST_CONFIG_PATH = "/desent/config/request"

    /** DataItem path carrying the serialized [WearInbox] payload. */
    const val INBOX_PATH = "/desent/inbox"

    /** Message path (watch → phone) requesting a fresh inbox push. */
    const val REQUEST_INBOX_PATH = "/desent/inbox/request"

    /** Message path (watch → phone) carrying [WearInboxPrefs] (spam toggle). */
    const val INBOX_PREFS_PATH = "/desent/inbox/prefs"

    /** DataItem path carrying the serialized [WearCalendar] payload. */
    const val CALENDAR_PATH = "/desent/calendar"

    /** Message path (watch → phone) requesting a fresh calendar push. */
    const val REQUEST_CALENDAR_PATH = "/desent/calendar/request"

    /** DataItem path carrying the serialized [WearBunkerRequest] payload. */
    const val BUNKER_PATH = "/desent/bunker"

    /** Message path (watch → phone) requesting a fresh bunker state push. */
    const val REQUEST_BUNKER_PATH = "/desent/bunker/request"

    /** Message path (watch → phone) carrying the user's [WearBunkerDecision]. */
    const val BUNKER_DECISION_PATH = "/desent/bunker/decision"

    /** Message path (watch → phone) carrying [WearBunkerPrefs] (sync toggle). */
    const val BUNKER_PREFS_PATH = "/desent/bunker/prefs"
}

/** Theme modes understood by the watch, mirroring the phone's ThemeMode. */
enum class WearThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    companion object {
        fun fromName(name: String?): WearThemeMode =
            entries.firstOrNull { it.name == name } ?: DARK
    }
}

/**
 * The config payload pushed to the watch. Fields default sensibly so older
 * payloads (and forward additions) decode without error.
 */
@Serializable
data class WearConfig(
    val themeMode: String = "DARK",
    val syncedAt: Long = 0L
)

/** JSON codec for [WearConfig]; tolerant of unknown/missing fields. */
object WearConfigCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(config: WearConfig): ByteArray =
        json.encodeToString(config).encodeToByteArray()

    fun decode(bytes: ByteArray): WearConfig? = try {
        json.decodeFromString<WearConfig>(bytes.decodeToString())
    } catch (e: Exception) {
        null
    }
}
