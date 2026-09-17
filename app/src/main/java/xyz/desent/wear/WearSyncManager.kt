package xyz.desent.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import xyz.desent.data.local.database.dao.EmailDao
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.data.nip46.Nip46BunkerService
import xyz.desent.data.wearsync.WearBunkerCodec
import xyz.desent.data.wearsync.WearBunkerRequest
import xyz.desent.data.wearsync.WearCalendar
import xyz.desent.data.wearsync.WearCalendarCodec
import xyz.desent.data.wearsync.WearConfig
import xyz.desent.data.wearsync.WearConfigCodec
import xyz.desent.data.wearsync.WearInbox
import xyz.desent.data.wearsync.WearInboxCodec
import xyz.desent.data.wearsync.WearSyncPaths
import xyz.desent.domain.model.PrivateContact
import xyz.desent.domain.model.NostrCalendarEvent
import xyz.desent.domain.repository.CalendarRepository
import xyz.desent.domain.repository.PrivateStorageRepository
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pushes the wear payloads to the DeSent wear app over the Wear OS Data
 * Layer API:
 *  - config (theme preference) — [pushConfig]
 *  - inbox snapshot (decrypted reading copies) — [pushInbox]
 *  - calendar snapshot (expanded occurrences + anniversaries) — [pushCalendar]
 *  - bunker prompt state (NIP-46 accept/deny mirror) — [pushBunkerState]
 *
 * Call sites:
 *  - app startup ([xyz.desent.DeSentApplication]) — self-healing re-push
 *  - [WearSyncService] when the watch explicitly requests a push
 *  - the reactive collectors (theme / inbox / calendar / bunker) in AppContainer
 *
 * The inbox and calendar payloads contain already-decrypted plaintext (the
 * phone unwraps NIP-59/NIP-44 at ingestion). The watch itself never
 * decrypts anything; bunker decisions come back as messages and the PHONE
 * signs.
 */
class WearSyncManager(
    context: Context,
    private val preferencesManager: PreferencesManager,
    private val emailDao: EmailDao,
    private val calendarRepository: CalendarRepository,
    private val privateStorageRepository: PrivateStorageRepository,
    private val nip46BunkerService: Nip46BunkerService
) {

    companion object {
        private const val TAG = "WearSyncManager"
    }

    private val appContext = context.applicationContext

    /** Build the current config and push it. */
    suspend fun pushConfig(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val config = WearConfig(
                themeMode = preferencesManager.themeMode.first().name,
                syncedAt = System.currentTimeMillis()
            )
            val request = PutDataRequest.create(WearSyncPaths.CONFIG_PATH)
                .setData(WearConfigCodec.encode(config))
            Wearable.getDataClient(appContext).putDataItem(request).await()
            Log.d(TAG, " Config pushed to wear (theme=${config.themeMode})")
            Unit
        }.onFailure { Log.w(TAG, "Config push failed: ${it.message}") }
    }

    /**
     * Build the current inbox snapshot and push it. With no active account
     * an empty payload is pushed, which clears the watch.
     */
    suspend fun pushInbox(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val npub = preferencesManager.getActiveNpub()
            val inbox = if (npub.isNullOrBlank()) {
                WearInbox(syncedAt = System.currentTimeMillis())
            } else {
                WearInboxBuilder.build(
                    threads = emailDao.observeThreads(npub).first(),
                    spamThreads = emailDao.observeSpamThreads(npub).first(),
                    spamEnabled = preferencesManager.wearSyncSpam.first(),
                    unreadCount = emailDao.getUnreadInboxCount(npub),
                    syncedAt = System.currentTimeMillis()
                )
            }
            val request = PutDataRequest.create(WearSyncPaths.INBOX_PATH)
                .setData(WearInboxCodec.encode(inbox))
            Wearable.getDataClient(appContext).putDataItem(request).await()
            Log.d(
                TAG,
                "✅ Inbox pushed to wear (emails=${inbox.emails.size}, spam=${inbox.spam.size}, " +
                    "unread=${inbox.unreadCount}, bytes=${request.data?.size ?: 0})"
            )
            Unit
        }.onFailure { Log.w(TAG, "Inbox push failed: ${it.message}") }
    }

    /**
     * Build the current calendar snapshot (today + 14 days, recurrence
     * expanded, contact anniversaries derived) and push it. With no active
     * account an empty payload is pushed, which clears the watch.
     */
    suspend fun pushCalendar(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val npub = preferencesManager.getActiveNpub()
            val calendar = if (npub.isNullOrBlank()) {
                WearCalendar(syncedAt = System.currentTimeMillis())
            } else {
                val events: List<NostrCalendarEvent> = calendarRepository.observeEvents(npub).first()
                val contacts: List<PrivateContact> =
                    privateStorageRepository.observeContacts(npub).first()
                WearCalendarBuilder.build(
                    events = events,
                    contacts = contacts,
                    today = LocalDate.now(),
                    zone = ZoneId.systemDefault(),
                    syncedAt = System.currentTimeMillis()
                )
            }
            val request = PutDataRequest.create(WearSyncPaths.CALENDAR_PATH)
                .setData(WearCalendarCodec.encode(calendar))
            Wearable.getDataClient(appContext).putDataItem(request).await()
            Log.d(
                TAG,
                "✅ Calendar pushed to wear (events=${calendar.events.size}, " +
                    "anniversaries=${calendar.anniversaries.size}, bytes=${request.data?.size ?: 0})"
            )
            Unit
        }.onFailure { Log.w(TAG, "Calendar push failed: ${it.message}") }
    }

    /**
     * Push the current bunker prompt state to the watch: the pending
     * NIP-46 prompt (label, method, preview, expiry) or a cleared payload
     * when nothing is pending. Time-sensitive, so callers push immediately
     * (no debounce). When the sync pref is off the payload still carries it
     * ([WearBunkerRequest.bunkerEnabled]) but never carries a request.
     */
    suspend fun pushBunkerState(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val bunkerEnabled = preferencesManager.wearSyncBunker.first()
            val prompt = if (bunkerEnabled) nip46BunkerService.pendingSignPrompt.value else null
            val request: WearBunkerRequest = WearBunkerBuilder.build(
                prompt = prompt,
                bunkerEnabled = bunkerEnabled
            )
            val putRequest = PutDataRequest.create(WearSyncPaths.BUNKER_PATH)
                .setData(WearBunkerCodec.encodeRequest(request))
            Wearable.getDataClient(appContext).putDataItem(putRequest).await()
            Log.d(
                TAG,
                "✅ Bunker state pushed to wear (requestId=${request.requestId?.take(12)}, " +
                    "enabled=$bunkerEnabled, bytes=${putRequest.data?.size ?: 0})"
            )
            Unit
        }.onFailure { Log.w(TAG, "Bunker state push failed: ${it.message}") }
    }
}
