package xyz.desent.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import xyz.desent.data.local.database.entity.BadgeNoticeEntity

/**
 * A badge-award notice joined with its (possibly retired) definition and
 * the matching kind 8 award. Definition/award legs are nullable so a
 * retired badge or post-notice revocation still renders the notice row.
 */
data class BadgeNoticeJoinRow(
    val eventId: String,
    val ownerNpub: String,
    val slug: String,
    val subject: String,
    val body: String,
    val receivedAt: Long,
    val isSeen: Boolean,
    val defName: String?,
    val defDescription: String?,
    val defImageUrl: String?,
    val defThumbUrl: String?,
    val defIconName: String?,
    val defColor: String?,
    val awardEventId: String?,
    val awardedAt: Long?
)

@Dao
interface BadgeNoticeDao {

    /** IGNORE keeps the first insert — gift-wrap event id dedup (§7). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(notice: BadgeNoticeEntity): Long

    @Query("SELECT * FROM badge_notices WHERE eventId = :eventId")
    suspend fun getById(eventId: String): BadgeNoticeEntity?

    @Query(
        """
        SELECT n.eventId AS eventId, n.ownerNpub AS ownerNpub, n.slug AS slug,
               n.subject AS subject, n.body AS body,
               n.receivedAt AS receivedAt, n.isSeen AS isSeen,
               d.name AS defName, d.description AS defDescription,
               d.imageUrl AS defImageUrl, d.thumbUrl AS defThumbUrl,
               d.iconName AS defIconName, d.color AS defColor,
               a.eventId AS awardEventId, a.awardedAt AS awardedAt
        FROM badge_notices n
        LEFT JOIN badge_definitions d ON d.slug = n.slug
        LEFT JOIN badge_awards a
               ON a.slug = n.slug AND a.awardeeNpub = n.ownerNpub
        WHERE n.ownerNpub = :npub
        ORDER BY n.receivedAt DESC
        """
    )
    fun observeForOwner(npub: String): Flow<List<BadgeNoticeJoinRow>>

    @Query("SELECT COUNT(*) FROM badge_notices WHERE ownerNpub = :npub AND isSeen = 0")
    fun observeUnseenCount(npub: String): Flow<Int>

    @Query("UPDATE badge_notices SET isSeen = 1 WHERE eventId = :eventId")
    suspend fun markSeen(eventId: String)

    @Query("UPDATE badge_notices SET isSeen = 1 WHERE ownerNpub = :npub AND isSeen = 0")
    suspend fun markAllSeen(npub: String)

    /**
     * Local mirror of the relay's fixed 30-day NIP-40 expiration on the
     * award-notice wraps (same retention as security alerts).
     */
    @Query("DELETE FROM badge_notices WHERE receivedAt < :cutoffMillis")
    suspend fun purgeOlderThan(cutoffMillis: Long)

    /** Bulk-delete notices for an account. Used by account removal. */
    @Query("DELETE FROM badge_notices WHERE ownerNpub = :npub")
    suspend fun deleteAllForOwner(npub: String)
}
