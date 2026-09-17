package xyz.desent.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import xyz.desent.data.local.database.entity.BadgeAwardEntity
import xyz.desent.data.local.database.entity.BadgeDefinitionEntity
import xyz.desent.data.local.database.entity.BadgePinEntity

/**
 * Flat join row for a badge that still resolves end-to-end: a pin whose
 * award exists and whose definition exists. Retired/revoked legs drop the
 * row (LEFT-join semantics would render stale badges — AND joins hide them,
 * per ANDROID_BADGES.md §5).
 */
data class BadgeJoinRow(
    val slug: String,
    val name: String,
    val description: String?,
    val imageUrl: String?,
    val thumbUrl: String?,
    val iconName: String?,
    val color: String?,
    val awardEventId: String,
    val awardeeNpub: String,
    val awardedAt: Long,
    val definitionAddress: String
)

@Dao
interface BadgeDao {

    // ---------------- Definitions (kind 30009) ----------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDefinition(definition: BadgeDefinitionEntity)

    @Query("SELECT * FROM badge_definitions WHERE slug = :slug")
    suspend fun getDefinition(slug: String): BadgeDefinitionEntity?

    @Query("DELETE FROM badge_definitions WHERE slug = :slug")
    suspend fun deleteDefinition(slug: String)

    @Query("DELETE FROM badge_definitions WHERE eventId = :eventId")
    suspend fun deleteDefinitionByEventId(eventId: String)

    // ---------------- Awards (kind 8) ----------------

    /** IGNORE keeps the first insert — award event ids are unique. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAward(award: BadgeAwardEntity): Long

    @Query("SELECT * FROM badge_awards WHERE eventId = :eventId")
    suspend fun getAward(eventId: String): BadgeAwardEntity?

    @Query("SELECT * FROM badge_awards WHERE awardeeNpub = :npub")
    suspend fun getAwardsFor(npub: String): List<BadgeAwardEntity>

    @Query("SELECT eventId FROM badge_awards WHERE awardeeNpub = :npub")
    suspend fun getAwardIdsFor(npub: String): List<String>

    @Query("SELECT * FROM badge_awards WHERE eventId IN (:eventIds)")
    suspend fun getAwardsByIds(eventIds: List<String>): List<BadgeAwardEntity>

    /** Revocation: drop the award and every pin that references it. */
    @Query("DELETE FROM badge_awards WHERE eventId = :eventId")
    suspend fun deleteAward(eventId: String)

    // ---------------- Pins (kind 30008) ----------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPins(pins: List<BadgePinEntity>)

    @Query("DELETE FROM badge_pins WHERE ownerNpub = :ownerNpub")
    suspend fun deletePinsFor(ownerNpub: String)

    @Query("DELETE FROM badge_pins WHERE awardEventId = :awardEventId")
    suspend fun deletePinsByAward(awardEventId: String)

    @Query("SELECT * FROM badge_pins WHERE ownerNpub = :ownerNpub ORDER BY position")
    suspend fun getPinsFor(ownerNpub: String): List<BadgePinEntity>

    @Transaction
    suspend fun replacePins(ownerNpub: String, pins: List<BadgePinEntity>) {
        deletePinsFor(ownerNpub)
        if (pins.isNotEmpty()) insertPins(pins)
    }

    // ---------------- Joined reads ----------------

    /**
     * A user's currently-displayed badges: pin ⨝ live award ⨝ live
     * definition, in pin order. Stale pairs (revoked award / retired
     * definition) fall out of the join automatically.
     */
    @Query(
        """
        SELECT d.slug AS slug, d.name AS name, d.description AS description,
               d.imageUrl AS imageUrl, d.thumbUrl AS thumbUrl,
               d.iconName AS iconName, d.color AS color,
               a.eventId AS awardEventId, a.awardeeNpub AS awardeeNpub,
               a.awardedAt AS awardedAt, a.definitionAddress AS definitionAddress
        FROM badge_pins p
        JOIN badge_awards a ON a.eventId = p.awardEventId
        JOIN badge_definitions d ON d.slug = a.slug
        WHERE p.ownerNpub = :npub
        ORDER BY p.position
        """
    )
    fun observePinnedBadges(npub: String): Flow<List<BadgeJoinRow>>

    /**
     * Every live award of [ownerNpub] joined with its definition, plus the
     * pinned flag — the profile editor's list. Awards whose definition was
     * retired are hidden (they cannot be pinned; the relay would reject).
     */
    @Query(
        """
        SELECT d.slug AS slug, d.name AS name, d.description AS description,
               d.imageUrl AS imageUrl, d.thumbUrl AS thumbUrl,
               d.iconName AS iconName, d.color AS color,
               a.eventId AS awardEventId, a.awardeeNpub AS awardeeNpub,
               a.awardedAt AS awardedAt, a.definitionAddress AS definitionAddress,
               (SELECT COUNT(*) FROM badge_pins p
                WHERE p.ownerNpub = :ownerNpub
                  AND p.awardEventId = a.eventId) > 0 AS isPinned
        FROM badge_awards a
        JOIN badge_definitions d ON d.slug = a.slug
        WHERE a.awardeeNpub = :ownerNpub
        ORDER BY a.awardedAt DESC
        """
    )
    fun observeEarnedBadges(ownerNpub: String): Flow<List<BadgeJoinRowWithPin>>
}

/** [BadgeJoinRow] + pin flag (earned badges read). */
data class BadgeJoinRowWithPin(
    val slug: String,
    val name: String,
    val description: String?,
    val imageUrl: String?,
    val thumbUrl: String?,
    val iconName: String?,
    val color: String?,
    val awardEventId: String,
    val awardeeNpub: String,
    val awardedAt: Long,
    val definitionAddress: String,
    val isPinned: Boolean
)
