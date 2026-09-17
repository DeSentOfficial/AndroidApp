package xyz.desent.data.nostr

import android.util.Log
import xyz.desent.data.local.database.dao.BadgeNoticeDao
import xyz.desent.data.local.database.entity.BadgeNoticeEntity
import xyz.desent.domain.model.BadgeAwardNotice

/**
 * Persists badge-award notices (relay-sealed kind-1010 `direction: "badge"`
 * gift wraps; refs/FromServer/ANDROID_BADGES.md §7) into `badge_notices`.
 *
 * The gift-wrap event id is the dedup key (IGNORE insert): [store] returns
 * true only on the FIRST insert of an id, which is exactly when callers
 * should fire the once-per-award OS notification / arrival flow. The relay
 * keeps the wraps for 30 days, so every fresh login re-downloads them —
 * without this persistence the notice (and its notification) re-fired on
 * every login. Same pattern as `security_alerts` ingestion.
 */
class BadgeNoticeHandler(
    private val badgeNoticeDao: BadgeNoticeDao
) {

    /** Store a notice; true iff this event id was not known before. */
    suspend fun store(notice: BadgeAwardNotice): Boolean {
        return try {
            val inserted = badgeNoticeDao.insert(
                BadgeNoticeEntity(
                    eventId = notice.eventId,
                    ownerNpub = notice.ownerNpub,
                    slug = notice.slug,
                    subject = notice.subject,
                    body = notice.body,
                    receivedAt = System.currentTimeMillis(),
                    isSeen = false
                )
            )
            inserted != -1L
        } catch (e: Exception) {
            Log.e(TAG, "failed to store badge notice: ${e.message}", e)
            false
        }
    }

    companion object {
        private const val TAG = "BadgeNoticeHandler"
    }
}
