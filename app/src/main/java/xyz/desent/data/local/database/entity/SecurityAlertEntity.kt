package xyz.desent.data.local.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A stored login-security alert (kind-1010 rumor, `direction: "security"`;
 * refs/FromServer/ANDROID_SECURITY_ALERTS.md). Kept in its own table —
 * separate from `emails` — so security alerts never render as ordinary
 * inbox mail and are never stamped by the spam filter.
 *
 * The relay fixes a 30-day NIP-40 expiration on the outer wrap; local rows
 * are purged on the same cadence ([xyz.desent.data.local.database.dao.SecurityAlertDao.purgeOlderThan]).
 */
@Entity(
    tableName = "security_alerts",
    indices = [Index("ownerNpub"), Index("receivedAt")]
)
data class SecurityAlertEntity(
    /** Gift-wrap event id — dedup key (insert IGNORE) + mark-seen key. */
    @PrimaryKey
    val eventId: String,
    val ownerNpub: String,
    val subject: String,
    /** "ws" | "http" | "admin". */
    val surface: String,
    /** Authoritative relay-clock time, "YYYY-MM-DD HH:MM UTC". */
    val time: String,
    val ip: String? = null,
    val geo: String? = null,
    val ua: String? = null,
    val device: String = "",
    val body: String = "",
    val receivedAt: Long = 0L,
    val isSeen: Boolean = false
)
