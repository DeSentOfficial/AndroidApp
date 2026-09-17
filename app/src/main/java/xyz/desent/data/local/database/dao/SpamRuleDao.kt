package xyz.desent.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import xyz.desent.data.local.database.entity.SpamRuleEntity

@Dao
interface SpamRuleDao {

    /** Single-row cache; replace whatever is there. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SpamRuleEntity)

    @Query("SELECT * FROM spam_rules WHERE id = 0")
    suspend fun get(): SpamRuleEntity?

    @Query("SELECT * FROM spam_rules WHERE id = 0")
    fun observe(): kotlinx.coroutines.flow.Flow<SpamRuleEntity?>

    @Query("DELETE FROM spam_rules")
    suspend fun clear()
}
