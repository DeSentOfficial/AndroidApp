package xyz.desent.domain.repository

import kotlinx.coroutines.flow.Flow
import xyz.desent.domain.model.Email
import xyz.desent.domain.model.SpamFilterConfig
import xyz.desent.domain.model.SpamListManifest
import xyz.desent.domain.model.SpamVerdict

/**
 * Client-side spam filter. All operations are local; no email content ever
 * leaves the device through this interface (see `refs/SPAM_FILTER_REFERENCE.md`).
 */
interface SpamFilterRepository {

    /** Classify a single email using the currently-enabled layers. */
    suspend fun classify(email: Email): SpamVerdict

    /**
     * Re-classify and persist the verdict onto [email] without training.
     * Used at ingestion and by the first-run retroactive pass.
     */
    suspend fun stamp(email: Email): Email

    /**
     * Record a user training signal and persist the resulting `isSpam` flag.
     * `isSpam=true` → "Mark as spam"; `isSpam=false` → "Not spam".
     */
    suspend fun trainAndMark(email: Email, isSpam: Boolean)

    /** Latest cached NIP-51 manifest (empty if none fetched yet). */
    fun observeManifest(): Flow<SpamListManifest>

    /**
     * Fetch the latest manifest from the trusted publisher over Nostr and
     * cache it if the version is newer. Returns true if the cache changed.
     */
    suspend fun syncManifest(): Boolean

    /** Observe the user's filter settings. */
    fun observeConfig(): Flow<SpamFilterConfig>

    /** Update the user's filter settings. */
    suspend fun setConfig(config: SpamFilterConfig)

    /** Observe spam-quarantined threads for an account. */
    fun observeSpamThreads(recipientNpub: String): Flow<List<Email>>

    /** Observe the count of quarantined messages (Spam folder badge). */
    fun observeSpamCount(recipientNpub: String): Flow<Int>

    /** Live count of learned Bayesian tokens for an owner (Spam policy stat). */
    fun observeTokenCount(ownerNpub: String): Flow<Int>

    /** Last successful manifest sync time (unix millis), for the settings screen. */
    fun observeLastSyncAt(): Flow<Long>

    /** Whether the first-run retroactive reclassification has completed for [ownerNpub]. */
    fun observeRetroFirstRunDone(ownerNpub: String): Flow<Boolean>

    /** Mark the first-run retroactive reclassification as complete for [ownerNpub]. */
    suspend fun setRetroFirstRunDone(ownerNpub: String, done: Boolean)

    /**
     * Clear all filter state for an account (Bayesian tokens + retro flag).
     * Called during account removal alongside [EmailRepository] cleanup.
     */
    suspend fun clearForAccount(ownerNpub: String)
}
