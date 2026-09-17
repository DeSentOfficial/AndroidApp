package xyz.desent.data.repository

import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import xyz.desent.crypto.SecureKeyManager
import xyz.desent.data.local.database.NostrDatabase
import xyz.desent.data.local.database.dao.AccountDao
import xyz.desent.data.local.database.dao.EmailDao
import xyz.desent.data.local.database.dao.FollowDao
import xyz.desent.data.local.database.entity.AccountEntity
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.data.mapper.AccountMapper
import xyz.desent.domain.model.Account
import xyz.desent.domain.repository.AccountRepository

/**
 * Implementation of [AccountRepository].
 *
 * The "active" account is owned by
 * [xyz.desent.data.session.SessionManager]; this class manages the persistent
 * account *list* and the per-account data lifecycle. Key material is held by
 * [SecureKeyManager], keyed per-npub.
 */
class AccountRepositoryImpl(
    private val database: NostrDatabase,
    private val accountDao: AccountDao,
    private val secureKeyManager: SecureKeyManager,
    private val preferencesManager: PreferencesManager,
    private val emailDao: EmailDao,
    private val followDao: FollowDao,
    private val privateNoteDao: xyz.desent.data.local.database.dao.PrivateNoteDao,
    private val calendarEventDao: xyz.desent.data.local.database.dao.CalendarEventDao,
    private val calendarDao: xyz.desent.data.local.database.dao.CalendarDao,
    private val calendarRsvpDao: xyz.desent.data.local.database.dao.CalendarRsvpDao,
    private val favoriteNoteDao: xyz.desent.data.local.database.dao.FavoriteNoteDao,
    private val privateContactsDao: xyz.desent.data.local.database.dao.PrivateContactsDao,
    private val accountMapper: AccountMapper = AccountMapper()
) : AccountRepository {

    override suspend fun addAccount(
        npub: String,
        nsec: String,
        requireBiometrics: Boolean
    ): Result<Unit> = runCatching {
        // Store the key. storeNSECForAccount also mirrors into the legacy
        // single-slot, making this new account the active one — callers
        // follow up with sessionManager.setActive(npub) to make that explicit.
        val keyResult = secureKeyManager.storeNSECForAccount(npub, nsec, requireBiometrics)
        if (keyResult.isFailure) throw keyResult.exceptionOrNull()!!

        val existing = accountDao.getAccount(npub)
        val now = System.currentTimeMillis()
        accountDao.upsertAccount(
            AccountEntity(
                npub = npub,
                displayName = existing?.displayName,
                picture = existing?.picture,
                nip05 = existing?.nip05,
                requiresBiometrics = requireBiometrics,
                lastActiveAt = existing?.lastActiveAt ?: now,
                addedAt = existing?.addedAt ?: now,
                custodialUsername = existing?.custodialUsername,
                primaryAddress = existing?.primaryAddress
            )
        )
    }

    override suspend fun updateAccountProfile(
        npub: String,
        displayName: String?,
        picture: String?,
        nip05: String?
    ) {
        // Only update if the account row exists; otherwise we'd resurrect a
        // row for an account that has been removed. Profile data for accounts
        // not-yet-added is held in the `users` table and picked up at add time.
        if (accountDao.getAccount(npub) != null) {
            accountDao.updateProfile(npub, displayName, picture, nip05)
        }
    }

    override suspend fun setAccountBiometricRequirement(
        npub: String,
        requireBiometrics: Boolean
    ) {
        accountDao.setRequiresBiometrics(npub, requireBiometrics)
    }

    override suspend fun setCustodialUsername(npub: String, username: String?) {
        accountDao.setCustodialUsername(npub, username)
    }

    override suspend fun getCustodialUsername(npub: String): String? {
        return accountDao.getCustodialUsername(npub)
    }

    override suspend fun removeAccount(npub: String): Result<Unit> = runCatching {
        Log.i(TAG, "Removing account $npub and all its scoped data")

        secureKeyManager.deleteAccountKey(npub)

        // content-npub-scoped tables
        emailDao.deleteAllForRecipient(npub)
        database.emailOutboxDao().deleteAllForRecipient(npub)
        database.securityAlertDao().deleteAllForOwner(npub)
        database.badgeNoticeDao().deleteAllForOwner(npub)
        // Delete both directions: the account's Following list (followerNpub)
        // AND its Followers list (followingNpub) so neither leaks into the
        // next account's view.
        followDao.deleteAllFollowsByFollower(npub)
        followDao.deleteAllFollowsByFollowing(npub)

        // NIP-78 private storage (notes + contacts) is per-account.
        privateNoteDao.deleteAllForOwner(npub)
        favoriteNoteDao.deleteAllForOwner(npub)
        privateContactsDao.deleteContacts(npub)
        database.userFileDao().deleteAllForOwner(npub)

        // NIP-52 encrypted calendar is per-account.
        calendarEventDao.deleteAllForOwner(npub)
        calendarDao.deleteAllForOwner(npub)
        calendarRsvpDao.deleteAllForOwner(npub)

        accountDao.deleteAccount(npub)
    }

    override suspend fun removeAllAccounts(): Result<Unit> = runCatching {
        val npubs = accountDao.getAccounts().map { it.npub }
        for (npub in npubs) {
            removeAccount(npub).getOrThrow()
        }
        // Clear the legacy active slot too.
        secureKeyManager.deleteNSECKey()
        preferencesManager.clearActiveNpub()
    }

    override suspend fun rotateLocalAccount(
        oldNpub: String,
        newNpub: String,
        newNsec: String,
        username: String?,
        keepOldKeyMaterial: Boolean
    ): Result<Unit> = runCatching {
        Log.i(TAG, "Re-pointing local account data $oldNpub -> $newNpub")

        val old = accountDao.getAccount(oldNpub)
            ?: throw IllegalStateException("No account row for $oldNpub")
        val now = System.currentTimeMillis()

        // 1. Store the new key FIRST — storeNSECForAccount mirrors it into the
        //    legacy single-slot, flipping every single-slot consumer (NIP-42,
        //    NIP-44, NIP-98, gift-wrap) to the new identity at once.
        secureKeyManager.storeNSECForAccount(newNpub, newNsec, false).getOrThrow()

        // 2. Roam the PGP keystore mirror alongside the 30078 it mirrors.
        secureKeyManager.getPgpKeyBlob(oldNpub)?.let { payload ->
            secureKeyManager.putPgpKeyBlob(newNpub, payload)
            if (!keepOldKeyMaterial) secureKeyManager.clearPgpKeyBlob(oldNpub)
        }

        // 3. Insert the new accounts row (copying the old profile fields) via
        //    the DAO so Room's invalidation tracker notifies observers.
        accountDao.upsertAccount(
            old.copy(
                npub = newNpub,
                requiresBiometrics = false,
                lastActiveAt = now,
                addedAt = now,
                custodialUsername = username ?: old.custodialUsername,
                primaryAddress = old.primaryAddress
            )
        )
        accountDao.setCustodialUsername(newNpub, username ?: old.custodialUsername)

        // 4. Re-own every account-scoped row. Raw SQL inside a Room
        //    transaction (same technique as the schema migrations); the UI
        //    performs a full nav-stack reset afterwards so Flow observers
        //    re-query regardless of invalidation.
        database.withTransaction {
            val db = database.openHelper.writableDatabase
            // Cached mail: wiped, not renamed — restored wraps carry new event
            // ids (the emails PK) and are re-delivered to the new subscription.
            db.execSQL("DELETE FROM emails WHERE recipientNpub = ?", arrayOf(oldNpub))
            // Inbound follows OF the old key are nobody's data now.
            db.execSQL("DELETE FROM follows WHERE followingNpub = ?", arrayOf(oldNpub))
            // Re-own outgoing follows AND rebuild the composite `id`
            // ("<follower>-<following>") so getFollowById lookups still hit.
            db.execSQL(
                "UPDATE follows SET id = ? || '-' || followingNpub, followerNpub = ? WHERE followerNpub = ?",
                arrayOf(newNpub, newNpub, oldNpub)
            )
            db.execSQL("UPDATE users SET npub = ? WHERE npub = ?", arrayOf(newNpub, oldNpub))
            for (table in OWNER_SCOPED_TABLES) {
                db.execSQL(
                    "UPDATE $table SET ownerNpub = ? WHERE ownerNpub = ?",
                    arrayOf(newNpub, oldNpub)
                )
            }
            db.execSQL(
                "UPDATE email_outbox SET recipientNpub = ? WHERE recipientNpub = ?",
                arrayOf(newNpub, oldNpub)
            )
        }

        // 5. Drop the old row + key material (the conversion flow keeps the
        //    key bytes for linked sign-in, but never the account row).
        accountDao.deleteAccount(oldNpub)
        if (!keepOldKeyMaterial) {
            secureKeyManager.deleteAccountKey(oldNpub)
        }
    }

    /**
     * One-shot migration from the legacy single-account storage.
     *
     * Conditions:
     *   - The `accounts` table is empty (first launch after v21 migration).
     *   - The legacy single-slot nsec is present in [SecureKeyManager].
     *
     * On success, every row in the previously-global tables that a prior SQL
     * migration left with `ownerNpub = ''` is stamped with the seeded npub.
     */
    override suspend fun seedFromLegacySingleAccount(): String? {
        if (accountDao.count() > 0) return null

        val seededNpub = secureKeyManager.seedPerAccountStoreIfMissing() ?: return null

        val legacyActiveNpub = preferencesManager.getActiveNpub()
        val npub = legacyActiveNpub ?: seededNpub
        if (legacyActiveNpub == null) {
            // npub was missing from prefs (e.g. cache cleared). Restore it.
            preferencesManager.setActiveNpub(seededNpub)
        }

        val now = System.currentTimeMillis()
        accountDao.upsertAccount(
            AccountEntity(
                npub = npub,
                displayName = null,
                picture = null,
                nip05 = null,
                requiresBiometrics = secureKeyManager.requiresBiometricsForAccount(npub),
                lastActiveAt = now,
                addedAt = now
            )
        )

        Log.i(TAG, "Seeded active account $npub from legacy single-account storage")
        return npub
    }

    override fun observeAccount(npub: String): Flow<Account?> {
        return accountDao.observeAccount(npub).map { it?.let(accountMapper::mapToDomain) }
    }

    override fun observeAllAccounts(): Flow<List<Account>> {
        return accountDao.observeAccountsJoined().map(accountMapper::mapToDomainList)
    }

    companion object {
        private const val TAG = "AccountRepository"

        /**
         * Every `ownerNpub`-keyed content table re-owned by
         * [rotateLocalAccount]. Keep in sync with the schema (v57 set).
         */
        private val OWNER_SCOPED_TABLES = listOf(
            "personal_spam_rules",
            "bayesian_tokens",
            "private_notes",
            "favorite_notes",
            "private_contacts",
            "calendar_events",
            "calendars",
            "calendar_rsvps",
            "security_alerts",
            "badge_pins",
            "badge_notices",
            "user_files",
            "mail_state",
            "mail_shard_state",
            "mail_folder_manifest"
        )
    }
}
