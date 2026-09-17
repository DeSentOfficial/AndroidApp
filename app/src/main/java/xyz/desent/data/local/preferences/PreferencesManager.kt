package xyz.desent.data.local.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "nostr_preferences")

class PreferencesManager(
    private val context: Context
) {
    val dataStore = context.dataStore

    private companion object {
        /** Delimiter for comma-joined string-list preferences (emails/domains). */
        const val LIST_DELIMITER = ","
    }

    private val KEY_NPUB = stringPreferencesKey("npub_key") // active account npub
    private val KEY_ENABLE_BIOMETRICS = booleanPreferencesKey("enable_biometrics")
    private val KEY_REMEMBER_ME = booleanPreferencesKey("remember_me")
    private val KEY_REQUIRE_PIN_ON_OPEN = booleanPreferencesKey("require_pin_on_open")
    private val KEY_REQUIRE_BIOMETRIC_ON_OPEN = booleanPreferencesKey("require_biometric_on_open")
    private val KEY_REQUIRE_BIOMETRIC_ON_SIGNING = booleanPreferencesKey("require_biometric_on_signing")
    private val KEY_USER_PIN = stringPreferencesKey("user_pin")
    private val KEY_APP_LOCK_RELOCK_SECONDS = longPreferencesKey("app_lock_relock_seconds")

    // Email Notification Settings
    private val KEY_IN_APP_NOTIFICATIONS_ENABLED = booleanPreferencesKey("in_app_notifications_enabled")
    private val KEY_EMAIL_NOTIFICATIONS_ENABLED = booleanPreferencesKey("email_notifications_enabled")
    private val KEY_BACKGROUND_SERVICE_ENABLED = booleanPreferencesKey("background_service_enabled")

    // Favicon source: desent.xyz server cache (on) vs direct sender-domain
    // probes (off). See refs/FROM_email.desent.xyz/FAVICON_CACHE.md.
    private val KEY_FAVICON_SERVER_CACHE_ENABLED = booleanPreferencesKey("favicon_server_cache_enabled")
    
    // Presence Settings

    // Theme Settings
    private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")

    // Spam Filter Settings (see refs/SPAM_FILTER_REFERENCE.md §"Settings")
    private val KEY_SPAM_FILTER_ENABLED = booleanPreferencesKey("spam_filter_enabled")
    private val KEY_SPAM_THRESHOLD = floatPreferencesKey("spam_threshold")
    private val KEY_SPAM_HEURISTIC_WEIGHT = floatPreferencesKey("spam_heuristic_weight")
    private val KEY_SPAM_BAYESIAN_WEIGHT = floatPreferencesKey("spam_bayesian_weight")
    private val KEY_SPAM_LAYER_HEURISTICS = booleanPreferencesKey("spam_layer_heuristics")
    private val KEY_SPAM_LAYER_BLOCKLIST = booleanPreferencesKey("spam_layer_blocklist")
    private val KEY_SPAM_LAYER_BAYESIAN = booleanPreferencesKey("spam_layer_bayesian")
    // Remote-image policy. List entries are comma-joined (emails/domains never
    // contain commas), lowercased + trimmed on write.
    private val KEY_BLOCK_REMOTE_IMAGES = booleanPreferencesKey("block_remote_images")
    private val KEY_IMAGE_ALLOWED_SENDERS = stringPreferencesKey("image_allowed_senders")
    private val KEY_IMAGE_ALLOWED_DOMAINS = stringPreferencesKey("image_allowed_domains")
    private val KEY_IMAGES_ALLOWED_FOR_CONTACTS = booleanPreferencesKey("images_allowed_for_contacts")
    private val KEY_SPAM_LIST_LAST_SYNC_AT = longPreferencesKey("spam_list_last_sync_at")

    // Cross-device sync watermarks for the NIP-78 spam namespaces
    // (desent:spam-settings / desent:spam-tokens). See SpamSettingsSyncCoordinator
    // and PrivateStorageRepositoryImpl.
    //  - last_seen_at: max created_at seen for config (publish or inbound) —
    //    drives last-write-wins + self-echo suppression.
    //  - tokens_last_sum: monotonic fingerprint of the last published token
    //    snapshot; a higher current sum means local training/merge is "dirty"
    //    and the next snapshot job should re-publish.
    //  - tokens_shard_count: shards published last time, for tombstone cleanup
    //    when the corpus shrinks below the previous shard count.
    private val KEY_SPAM_SETTINGS_LAST_SEEN_AT = longPreferencesKey("spam_settings_last_seen_at")
    private val KEY_SPAM_TOKENS_LAST_SUM = longPreferencesKey("spam_tokens_last_sum")
    private val KEY_SPAM_TOKENS_SHARD_COUNT = longPreferencesKey("spam_tokens_shard_count")
    private val KEY_PREVIEW_DETAIL_COMPONENTS = intPreferencesKey("preview_detail_components")

    private val KEY_MAIL_FOLDERS_LAST_SEEN_AT = longPreferencesKey("mail_folders_last_seen_at")

    // Wear OS companion sync. The spam toggle is owned by the phone (the
    // watch sends a request over the Data Layer; the phone persists it here
    // and mirrors it in every inbox payload).
    private val KEY_WEAR_SYNC_SPAM = booleanPreferencesKey("wear_sync_spam")
    private val KEY_WEAR_SYNC_BUNKER = booleanPreferencesKey("wear_sync_bunker")

    // ==================== ACTIVE ACCOUNT ====================
    //
    // The npub stored under KEY_NPUB is the *active* account: the account
    // whose identity is loaded into NostrRepository / NostrEventProcessor and
    // whose data is subscribed and ingested. Switching accounts writes
    // a different npub here. The full set of saved accounts lives in the
    // `accounts` Room table.

    suspend fun saveNpubKey(npub: String) {
        dataStore.edit { preferences ->
            preferences[KEY_NPUB] = npub
        }
    }

    /** Alias for [saveNpubKey] used by multi-account code. */
    suspend fun setActiveNpub(npub: String) = saveNpubKey(npub)

    suspend fun clearActiveNpub() {
        dataStore.edit { preferences ->
            preferences.remove(KEY_NPUB)
        }
    }

    val npubKey: Flow<String?> = dataStore.data.map { preferences ->
        preferences[KEY_NPUB]
    }

    /** Alias for [npubKey]; the active account's npub as a reactive flow. */
    val activeNpub: Flow<String?> get() = npubKey

    suspend fun getActiveNpub(): String? = npubKey.firstOrNull()

    // Biometric Settings
    suspend fun setBiometricEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_ENABLE_BIOMETRICS] = enabled
        }
    }

    val isBiometricEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[KEY_ENABLE_BIOMETRICS] ?: false
    }

    // Remember Me Setting
    suspend fun setRememberMe(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_REMEMBER_ME] = enabled
        }
    }

    val isRememberMeEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[KEY_REMEMBER_ME] ?: false
    }

    // Security Settings
    suspend fun setRequirePinOnOpen(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_REQUIRE_PIN_ON_OPEN] = enabled
        }
    }

    val isRequirePinOnOpenEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[KEY_REQUIRE_PIN_ON_OPEN] ?: false
    }

    suspend fun setRequireBiometricOnOpen(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_REQUIRE_BIOMETRIC_ON_OPEN] = enabled
        }
    }

    val isRequireBiometricOnOpenEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[KEY_REQUIRE_BIOMETRIC_ON_OPEN] ?: false
    }

    suspend fun setRequireBiometricOnSigning(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_REQUIRE_BIOMETRIC_ON_SIGNING] = enabled
        }
    }

    val isRequireBiometricOnSigningEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[KEY_REQUIRE_BIOMETRIC_ON_SIGNING] ?: false
    }

    suspend fun setUserPin(pin: String) {
        dataStore.edit { preferences ->
            preferences[KEY_USER_PIN] = pin
        }
    }

    val userPin: Flow<String?> = dataStore.data.map { preferences ->
        preferences[KEY_USER_PIN]
    }

    /**
     * How long the app may stay backgrounded before the lock screen is shown
     * again on return to the foreground. `0` (default) = re-lock on every
     * foreground transition; otherwise the value is in seconds.
     */
    suspend fun setAppLockRelockSeconds(seconds: Long) {
        dataStore.edit { preferences ->
            preferences[KEY_APP_LOCK_RELOCK_SECONDS] = seconds
        }
    }

    val appLockRelockSeconds: Flow<Long> = dataStore.data.map { preferences ->
        preferences[KEY_APP_LOCK_RELOCK_SECONDS] ?: 0L
    }
        .onStart { emit(0L) }
        .catch { emit(0L) }

    // In-App Notifications
    suspend fun setInAppNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_IN_APP_NOTIFICATIONS_ENABLED] = enabled
        }
    }

    val areInAppNotificationsEnabled: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[KEY_IN_APP_NOTIFICATIONS_ENABLED] ?: true  // Default enabled
        }
        .onStart { emit(true) }  // Emit default immediately
        .catch { emit(true) }  // Emit default on any error

    // Email Notifications (system tray)
    suspend fun setEmailNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_EMAIL_NOTIFICATIONS_ENABLED] = enabled
        }
    }

    val areEmailNotificationsEnabled: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[KEY_EMAIL_NOTIFICATIONS_ENABLED] ?: true
        }
        .onStart { emit(true) }
        .catch { emit(true) }

    // Background service (foreground service that keeps relays alive)
    suspend fun setBackgroundServiceEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_BACKGROUND_SERVICE_ENABLED] = enabled
        }
    }

    val isBackgroundServiceEnabled: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[KEY_BACKGROUND_SERVICE_ENABLED] ?: false  // Default disabled (opt-in)
        }
        .onStart { emit(false) }
        .catch { emit(false) }

    // Favicon source (server cache vs direct origin probes). Default ON:
    // warm hits, and the sender domains already transit desent.xyz via the
    // email bridge, so routing lookups through it adds no new disclosure
    // while removing N client IPs from the picture.
    suspend fun setFaviconServerCacheEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_FAVICON_SERVER_CACHE_ENABLED] = enabled
        }
    }

    val isFaviconServerCacheEnabled: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[KEY_FAVICON_SERVER_CACHE_ENABLED] ?: true
        }
        .onStart { emit(true) }
        .catch { emit(true) }

    // Presence Settings        .onStart { emit(false) }  // Emit default immediately
        .catch { emit(false) }  // Emit default on any error

    // Theme Settings
    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { preferences ->
            preferences[KEY_THEME_MODE] = mode.name
        }
    }

    val themeMode: Flow<ThemeMode> = dataStore.data
        .map { preferences ->
            val name = preferences[KEY_THEME_MODE]
            try {
                ThemeMode.valueOf(name ?: "SYSTEM")
            } catch (e: Exception) {
                ThemeMode.SYSTEM  // Default
            }
        }
        .onStart { emit(ThemeMode.SYSTEM) }  // Emit default immediately
        .catch { emit(ThemeMode.SYSTEM) }  // Emit default on any error

    // ==================== WEAR OS SYNC ====================

    suspend fun setWearSyncSpam(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_WEAR_SYNC_SPAM] = enabled
        }
    }

    val wearSyncSpam: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[KEY_WEAR_SYNC_SPAM] ?: true
        }
        .onStart { emit(true) }  // Emit default immediately
        .catch { emit(true) }  // Emit default on any error

    suspend fun setWearSyncBunker(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_WEAR_SYNC_BUNKER] = enabled
        }
    }

    val wearSyncBunker: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[KEY_WEAR_SYNC_BUNKER] ?: true
        }
        .onStart { emit(true) }  // Emit default immediately
        .catch { emit(true) }  // Emit default on any error

    // Clear all preferences
    suspend fun clearAll() {
        dataStore.edit { preferences ->
            preferences.clear()
        }
    }

    // ==================== SPAM FILTER ====================
    // See refs/SPAM_FILTER_REFERENCE.md §"Settings". Defaults produce the
    // scoring documented there.

    val spamFilterConfig: Flow<xyz.desent.domain.model.SpamFilterConfig> = dataStore.data
        .map { preferences ->
            xyz.desent.domain.model.SpamFilterConfig(
                enabled = preferences[KEY_SPAM_FILTER_ENABLED] ?: true,
                threshold = (preferences[KEY_SPAM_THRESHOLD] ?: 5.0f).toDouble(),
                heuristicWeight = (preferences[KEY_SPAM_HEURISTIC_WEIGHT] ?: 0.6f).toDouble(),
                bayesianWeight = (preferences[KEY_SPAM_BAYESIAN_WEIGHT] ?: 0.4f).toDouble(),
                layerHeuristicsEnabled = preferences[KEY_SPAM_LAYER_HEURISTICS] ?: true,
                layerBlocklistEnabled = preferences[KEY_SPAM_LAYER_BLOCKLIST] ?: true,
                layerBayesianEnabled = preferences[KEY_SPAM_LAYER_BAYESIAN] ?: true,
                blockRemoteImages = preferences[KEY_BLOCK_REMOTE_IMAGES] ?: true,
                imageAllowedSenders = preferences[KEY_IMAGE_ALLOWED_SENDERS]
                    ?.split(LIST_DELIMITER)?.filter { it.isNotEmpty() } ?: emptyList(),
                imageAllowedDomains = preferences[KEY_IMAGE_ALLOWED_DOMAINS]
                    ?.split(LIST_DELIMITER)?.filter { it.isNotEmpty() } ?: emptyList(),
                imagesAllowedForContacts = preferences[KEY_IMAGES_ALLOWED_FOR_CONTACTS] ?: true
            )
        }
        .onStart { emit(xyz.desent.domain.model.SpamFilterConfig()) }
        .catch { emit(xyz.desent.domain.model.SpamFilterConfig()) }

    suspend fun setSpamFilterConfig(config: xyz.desent.domain.model.SpamFilterConfig) {
        dataStore.edit { preferences ->
            preferences[KEY_SPAM_FILTER_ENABLED] = config.enabled
            preferences[KEY_SPAM_THRESHOLD] = config.threshold.toFloat()
            preferences[KEY_SPAM_HEURISTIC_WEIGHT] = config.heuristicWeight.toFloat()
            preferences[KEY_SPAM_BAYESIAN_WEIGHT] = config.bayesianWeight.toFloat()
            preferences[KEY_SPAM_LAYER_HEURISTICS] = config.layerHeuristicsEnabled
            preferences[KEY_SPAM_LAYER_BLOCKLIST] = config.layerBlocklistEnabled
            preferences[KEY_SPAM_LAYER_BAYESIAN] = config.layerBayesianEnabled
            preferences[KEY_BLOCK_REMOTE_IMAGES] = config.blockRemoteImages
            preferences[KEY_IMAGE_ALLOWED_SENDERS] = config.imageAllowedSenders.joinToString(LIST_DELIMITER)
            preferences[KEY_IMAGE_ALLOWED_DOMAINS] = config.imageAllowedDomains.joinToString(LIST_DELIMITER)
            preferences[KEY_IMAGES_ALLOWED_FOR_CONTACTS] = config.imagesAllowedForContacts
        }
    }

    val spamListLastSyncAt: Flow<Long> = dataStore.data
        .map { preferences -> preferences[KEY_SPAM_LIST_LAST_SYNC_AT] ?: 0L }
        .onStart { emit(0L) }
        .catch { emit(0L) }

    suspend fun setSpamListLastSyncAt(timestampMs: Long) {
        dataStore.edit { it[KEY_SPAM_LIST_LAST_SYNC_AT] = timestampMs }
    }

    /**
     * Per-account: whether the first-run retroactive reclassification has
     * completed for this npub. Keyed by account (not global) so a DataStore
     * `clearAll()` on logout doesn't cause a full re-stamp that could fight
     * user verdicts on the next login.
     */
    fun spamRetroFirstRunDone(npub: String): Flow<Boolean> {
        val key = booleanPreferencesKey("spam_retro_first_run_done_${npub}")
        return dataStore.data
            .map { preferences -> preferences[key] ?: false }
            .onStart { emit(false) }
            .catch { emit(false) }
    }

    suspend fun setSpamRetroFirstRunDone(npub: String, done: Boolean) {
        val key = booleanPreferencesKey("spam_retro_first_run_done_${npub}")
        dataStore.edit { it[key] = done }
    }

    // ==================== SPAM SYNC WATERMARKS ====================
    // Used by PrivateStorageRepositoryImpl + SpamSettingsSyncCoordinator for
    // last-write-wins + echo suppression (config) and delta-gated sharded
    // snapshots (tokens). See the watermark keys above.

    val spamSettingsLastSeenAt: Flow<Long> = dataStore.data
        .map { it[KEY_SPAM_SETTINGS_LAST_SEEN_AT] ?: 0L }
        .onStart { emit(0L) }
        .catch { emit(0L) }

    suspend fun setSpamSettingsLastSeenAt(epochSeconds: Long) {
        dataStore.edit { it[KEY_SPAM_SETTINGS_LAST_SEEN_AT] = epochSeconds }
    }

    val spamTokensLastSum: Flow<Long> = dataStore.data
        .map { it[KEY_SPAM_TOKENS_LAST_SUM] ?: 0L }
        .onStart { emit(0L) }
        .catch { emit(0L) }

    suspend fun setSpamTokensLastSum(sum: Long) {
        dataStore.edit { it[KEY_SPAM_TOKENS_LAST_SUM] = sum }
    }

    /** Last number of token-snapshot shards we published (for tombstone cleanup). */
    val spamTokensShardCount: Flow<Int> = dataStore.data
        .map { (it[KEY_SPAM_TOKENS_SHARD_COUNT] ?: 0L).toInt() }
        .onStart { emit(0) }
        .catch { emit(0) }

    suspend fun setSpamTokensShardCount(count: Int) {
        dataStore.edit { it[KEY_SPAM_TOKENS_SHARD_COUNT] = count.toLong() }
    }

    // ==================== USER FILES (END-23) ====================
    /**
     * Blurhash component count used when encoding previews for uploaded
     * images (1 = very blurry, 9 = max detail). The last value chosen on the
     * preview-quality slider; 8 (→ an 8×6 grid) is the END-23 default.
     */
    suspend fun setPreviewDetailComponents(components: Int) {
        dataStore.edit { it[KEY_PREVIEW_DETAIL_COMPONENTS] = components.coerceIn(1, 9) }
    }

    val previewDetailComponents: Flow<Int> = dataStore.data
        .map { preferences -> preferences[KEY_PREVIEW_DETAIL_COMPONENTS] ?: 8 }
        .onStart { emit(8) }
        .catch { emit(8) }

    // ==================== MAIL FOLDERS (kind 30078 overlay) ====================
    // LWW watermark for the `desent:mail-folders` manifest (echo suppression —
    // identical to spamSettingsLastSeenAt). See refs/FROM_email.desent.xyz/
    // ANDROID_MAIL_FOLDERS.md.

    val mailFoldersLastSeenAt: Flow<Long> = dataStore.data
        .map { it[KEY_MAIL_FOLDERS_LAST_SEEN_AT] ?: 0L }
        .onStart { emit(0L) }
        .catch { emit(0L) }

    suspend fun setMailFoldersLastSeenAt(epochSeconds: Long) {
        dataStore.edit { it[KEY_MAIL_FOLDERS_LAST_SEEN_AT] = epochSeconds }
    }
}