package xyz.desent.data.wearsync

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Shared phone→watch inbox sync contract.
 *
 * The phone is the only party that ever decrypts mail (NIP-44 unwrap happens
 * at ingestion on the phone). What reaches the watch via this payload is the
 * already-decrypted, HTML-stripped reading copy — the watch performs no
 * crypto and contacts no relays. PGP-armored mail stays ciphertext even on
 * the phone, so it travels as [WearEmail.isPgp] with a placeholder body and
 * is fully readable only via "Open on phone".
 *
 * Size budget: the payload rides a DataItem (100KB data-layer limit). The
 * phone-side builder caps entry counts and body length so the gzipped JSON
 * stays comfortably under it.
 */

/** One readable inbox/spam entry on the watch. */
@Serializable
data class WearEmail(
    val id: String = "",
    /** Thread grouping key — also the `threadKey` arg of the phone's `desent://email` deep link. */
    val threadKey: String = "",
    val senderName: String = "",
    val senderEmail: String = "",
    val subject: String = "",
    /** Decrypted, HTML-stripped plaintext (placeholder for PGP mail). */
    val body: String = "",
    val createdAt: Long = 0L,
    val isRead: Boolean = false,
    val isPgp: Boolean = false,
    val attachmentCount: Int = 0
)

/** Inbox snapshot pushed as one DataItem. */
@Serializable
data class WearInbox(
    /** Latest message per thread (read + unread), newest first. */
    val emails: List<WearEmail> = emptyList(),
    /** Spam threads, newest first. Empty when [spamEnabled] is false. */
    val spam: List<WearEmail> = emptyList(),
    /** Whether the user wants spam synced/shown on the watch. */
    val spamEnabled: Boolean = true,
    /** Unread inbox count (spam + SYSTEM excluded) — mirrors the phone badge. */
    val unreadCount: Int = 0,
    /** Uniqueness stamp so identical content still triggers a Data Layer change event. */
    val syncedAt: Long = 0L
)

/**
 * GZIP + JSON codec for [WearInbox]. Bodies are plaintext prose, which
 * compresses ~4-5x; gzipping keeps the 25+25 full-text entries well inside
 * the DataItem budget.
 */
object WearInboxCodec {
    fun encode(inbox: WearInbox): ByteArray = WearSyncGzip.encodeGzipped(inbox)

    fun decode(bytes: ByteArray): WearInbox? = WearSyncGzip.decodeGzipped(bytes)
}

/** Watch→phone prefs message body (currently: the spam sync toggle). */
@Serializable
data class WearInboxPrefs(
    val spamEnabled: Boolean = true
)

object WearInboxPrefsCodec {
    fun encode(prefs: WearInboxPrefs): ByteArray =
        WearSyncGzip.json.encodeToString(prefs).encodeToByteArray()

    fun decode(bytes: ByteArray): WearInboxPrefs? = try {
        WearSyncGzip.json.decodeFromString<WearInboxPrefs>(bytes.decodeToString())
    } catch (e: Exception) {
        null
    }
}
