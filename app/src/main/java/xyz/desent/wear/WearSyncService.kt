package xyz.desent.wear

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import xyz.desent.di.AppContainer
import xyz.desent.data.wearsync.WearBunkerCodec
import xyz.desent.data.wearsync.WearBunkerDecision
import xyz.desent.data.wearsync.WearBunkerPrefs
import xyz.desent.data.wearsync.WearInboxPrefsCodec
import xyz.desent.data.wearsync.WearSyncPaths

/**
 * Phone-side Data Layer listener. The watch sends requests when it has no
 * (or stale) data — e.g. first launch after installation — and this service
 * responds by pushing a fresh payload:
 *  - config request    → [WearSyncManager.pushConfig]
 *  - inbox request     → [WearSyncManager.pushInbox]
 *  - calendar request  → [WearSyncManager.pushCalendar]
 *  - inbox prefs       → persist the spam toggle, then re-push the inbox
 *  - bunker request    → [WearSyncManager.pushBunkerState]
 *  - bunker prefs      → persist the bunker toggle, then re-push the state
 *  - bunker decision   → resolve the pending NIP-46 prompt (the PHONE signs;
 *    the watch only carries the user's accept/deny)
 */
class WearSyncService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(event: MessageEvent) {
        val container = AppContainer.getInstance(applicationContext)
        scope.launch {
            when (event.path) {
                WearSyncPaths.REQUEST_CONFIG_PATH ->
                    container.wearSyncManager.pushConfig()

                WearSyncPaths.REQUEST_INBOX_PATH ->
                    container.wearSyncManager.pushInbox()

                WearSyncPaths.REQUEST_CALENDAR_PATH ->
                    container.wearSyncManager.pushCalendar()

                WearSyncPaths.INBOX_PREFS_PATH -> {
                    val prefs = WearInboxPrefsCodec.decode(event.data) ?: return@launch
                    container.preferencesManager.setWearSyncSpam(prefs.spamEnabled)
                    container.wearSyncManager.pushInbox()
                }

                WearSyncPaths.REQUEST_BUNKER_PATH ->
                    container.wearSyncManager.pushBunkerState()

                WearSyncPaths.BUNKER_PREFS_PATH -> {
                    val prefs = WearBunkerCodec.decodePrefs(event.data) ?: return@launch
                    container.preferencesManager.setWearSyncBunker(prefs.enabled)
                    // Re-push so the watch's toggle renders persisted truth; a
                    // just-disabled sync also clears any live request in it.
                    container.wearSyncManager.pushBunkerState()
                }

                WearSyncPaths.BUNKER_DECISION_PATH -> {
                    val decision: WearBunkerDecision =
                        WearBunkerCodec.decodeDecision(event.data) ?: return@launch
                    // Match the request id so a late decision (the phone's
                    // auto-deny already fired) can never approve a *newer*
                    // request that happened to reuse the prompt slot.
                    val pendingId = container.nip46BunkerService.pendingSignPrompt.value?.requestId
                    if (pendingId != decision.requestId) return@launch
                    if (decision.accept) {
                        container.nip46BunkerService.approvePending(decision.alwaysAllow)
                    } else {
                        container.nip46BunkerService.denyPending()
                    }
                    // Clearing the slot re-triggers startWearBunkerSync, which
                    // pushes the cleared state back to the watch.
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
