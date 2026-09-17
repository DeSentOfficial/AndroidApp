package xyz.desent.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import xyz.desent.data.local.database.entity.PersonalSpamRuleEntity

@Dao
interface PersonalSpamRuleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PersonalSpamRuleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<PersonalSpamRuleEntity>)

    @Query("SELECT * FROM personal_spam_rules WHERE ownerNpub = :ownerNpub")
    suspend fun getForOwner(ownerNpub: String): List<PersonalSpamRuleEntity>

    /** Live view of every rule — drives the NIP-78 settings sync coordinator. */
    @Query("SELECT * FROM personal_spam_rules")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<PersonalSpamRuleEntity>>

    @Query("DELETE FROM personal_spam_rules WHERE ownerNpub = :ownerNpub AND type = :type AND value = :value")
    suspend fun delete(ownerNpub: String, type: String, value: String)

    @Query("DELETE FROM personal_spam_rules WHERE ownerNpub = :ownerNpub")
    suspend fun clearForOwner(ownerNpub: String)

    @Query("DELETE FROM personal_spam_rules")
    suspend fun clearAll()
}
