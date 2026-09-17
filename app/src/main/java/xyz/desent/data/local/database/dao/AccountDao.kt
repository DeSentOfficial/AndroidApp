package xyz.desent.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import xyz.desent.data.local.database.entity.AccountEntity

@Dao
interface AccountDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAccount(account: AccountEntity)

    @Query("SELECT * FROM accounts WHERE npub = :npub LIMIT 1")
    suspend fun getAccount(npub: String): AccountEntity?

    @Query("SELECT * FROM accounts ORDER BY lastActiveAt DESC")
    fun observeAllAccounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY lastActiveAt DESC")
    suspend fun getAccounts(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE npub = :npub LIMIT 1")
    fun observeAccount(npub: String): Flow<AccountEntity?>

    @Query("UPDATE accounts SET lastActiveAt = :timestamp WHERE npub = :npub")
    suspend fun setLastActiveAt(npub: String, timestamp: Long)

    @Query("UPDATE accounts SET displayName = :displayName, picture = :picture, nip05 = :nip05 WHERE npub = :npub")
    suspend fun updateProfile(npub: String, displayName: String?, picture: String?, nip05: String?)

    @Query("UPDATE accounts SET requiresBiometrics = :required WHERE npub = :npub")
    suspend fun setRequiresBiometrics(npub: String, required: Boolean)

    @Query("UPDATE accounts SET custodialUsername = :username WHERE npub = :npub")
    suspend fun setCustodialUsername(npub: String, username: String?)

    @Query("UPDATE accounts SET primaryAddress = :address WHERE npub = :npub")
    suspend fun setPrimaryAddress(npub: String, address: String?)

    @Query("SELECT custodialUsername FROM accounts WHERE npub = :npub LIMIT 1")
    suspend fun getCustodialUsername(npub: String): String?

    @Query("DELETE FROM accounts WHERE npub = :npub")
    suspend fun deleteAccount(npub: String)

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun count(): Int

    /**
     * Observe all accounts, joined with the `users` table so the freshest
     * kind-0 profile (displayName / picture / nip05) is surfaced reactively.
     *
     * The `users` table is written by `NostrEventProcessor.processMetadataEvent`
     * every time a kind-0 event arrives from a relay. COALESCE prefers the
     * `users` value and falls back to whatever was stored directly on the
     * `accounts` row (e.g. from registration) when no kind-0 has arrived yet.
     * `primaryAddress` is NOT coalesced — it is the registered address from
     * the API, authoritative over any kind-0 nip05.
     *
     * This drives the account-switcher list AND the avatar in the bottom nav,
     * so both update automatically when profile data trickles in from relays.
     */
    @Query(
        """
        SELECT a.npub,
               COALESCE(u.displayName, a.displayName) AS displayName,
               COALESCE(u.picture,    a.picture)     AS picture,
               COALESCE(u.nip05,      a.nip05)       AS nip05,
               a.requiresBiometrics,
               a.lastActiveAt,
               a.addedAt,
               a.custodialUsername,
               a.primaryAddress
        FROM accounts a
        LEFT JOIN users u ON a.npub = u.npub
        ORDER BY a.lastActiveAt DESC
        """
    )
    fun observeAccountsJoined(): Flow<List<AccountEntity>>

    /**
     * Observe a single account joined with its kind-0 profile, see
     * [observeAccountsJoined] for the join rationale.
     */
    @Query(
        """
        SELECT a.npub,
               COALESCE(u.displayName, a.displayName) AS displayName,
               COALESCE(u.picture,    a.picture)     AS picture,
               COALESCE(u.nip05,      a.nip05)       AS nip05,
               a.requiresBiometrics,
               a.lastActiveAt,
               a.addedAt,
               a.custodialUsername,
               a.primaryAddress
        FROM accounts a
        LEFT JOIN users u ON a.npub = u.npub
        WHERE a.npub = :npub
        LIMIT 1
        """
    )
    fun observeAccountJoined(npub: String): Flow<AccountEntity?>
}
