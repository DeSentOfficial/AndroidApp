package xyz.desent.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import xyz.desent.data.local.database.entity.EmailForwardLedgerEntity

@Dao
interface EmailForwardLedgerDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: EmailForwardLedgerEntity)

    @Query("SELECT * FROM email_forward_ledger WHERE emailId = :emailId AND targetNpub = :targetNpub")
    suspend fun get(emailId: String, targetNpub: String): EmailForwardLedgerEntity?

    @Query("SELECT emailId FROM email_forward_ledger WHERE targetNpub = :targetNpub AND status = :status")
    suspend fun getEmailIdsByStatus(targetNpub: String, status: String): List<String>
}
