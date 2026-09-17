package xyz.desent.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import xyz.desent.data.local.database.entity.BayesianTokenEntity

@Dao
interface BayesianTokenDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BayesianTokenEntity)

    @Query("SELECT * FROM bayesian_tokens WHERE tokenHash IN (:hashes)")
    suspend fun get(hashes: List<String>): List<BayesianTokenEntity>

    @Query("SELECT * FROM bayesian_tokens WHERE tokenHash IN (:hashes)")
    fun observe(hashes: List<String>): kotlinx.coroutines.flow.Flow<List<BayesianTokenEntity>>

    /** All tokens for an owner — used to build the NIP-78 snapshot for cross-device sync. */
    @Query("SELECT * FROM bayesian_tokens WHERE ownerNpub = :ownerNpub")
    suspend fun getAllForOwner(ownerNpub: String): List<BayesianTokenEntity>

    /** Live count of learned tokens for an owner — for the Spam policy screen stat. */
    @Query("SELECT COUNT(*) FROM bayesian_tokens WHERE ownerNpub = :ownerNpub")
    fun countForOwner(ownerNpub: String): kotlinx.coroutines.flow.Flow<Int>

    /**
     * Merge a remote snapshot row into the local table taking the element-wise
     * maximum of spam/ham counts. `max` is a convergent state-CRDT merge: it
     * never inflates counts (vs sum double-counting shared training emails) and
     * never loses the higher-water-mark training (vs plain replace). Used when
     * applying an inbound kind-30078 token snapshot from another device.
     */
    @Query(
        """
        INSERT INTO bayesian_tokens (tokenHash, ownerNpub, spamCount, hamCount)
        VALUES (:hash, :ownerNpub, :spam, :ham)
        ON CONFLICT(tokenHash) DO UPDATE SET
            spamCount = MAX(spamCount, :spam),
            hamCount  = MAX(hamCount,  :ham)
        """
    )
    suspend fun upsertMax(hash: String, ownerNpub: String, spam: Int, ham: Int)

    @Query(
        """
        INSERT INTO bayesian_tokens (tokenHash, ownerNpub, spamCount, hamCount)
        VALUES (:hash, :ownerNpub, :spamDelta, :hamDelta)
        ON CONFLICT(tokenHash) DO UPDATE SET
            spamCount = spamCount + :spamDelta,
            hamCount  = hamCount  + :hamDelta
        """
    )
    suspend fun bumpCounts(hash: String, ownerNpub: String, spamDelta: Int, hamDelta: Int)

    @Query("DELETE FROM bayesian_tokens WHERE ownerNpub = :ownerNpub")
    suspend fun clearForOwner(ownerNpub: String)

    @Query("DELETE FROM bayesian_tokens")
    suspend fun clearAll()
}
