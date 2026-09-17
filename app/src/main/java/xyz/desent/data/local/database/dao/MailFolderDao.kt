package xyz.desent.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import xyz.desent.data.local.database.entity.MailFolderManifestEntity

@Dao
interface MailFolderDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertManifest(entity: MailFolderManifestEntity)

    @Query("SELECT * FROM mail_folder_manifest WHERE ownerNpub = :ownerNpub")
    fun observeManifest(ownerNpub: String): Flow<MailFolderManifestEntity?>

    @Query("SELECT * FROM mail_folder_manifest WHERE ownerNpub = :ownerNpub")
    suspend fun getManifest(ownerNpub: String): MailFolderManifestEntity?

    @Query("DELETE FROM mail_folder_manifest WHERE ownerNpub = :ownerNpub")
    suspend fun deleteManifest(ownerNpub: String)
}
