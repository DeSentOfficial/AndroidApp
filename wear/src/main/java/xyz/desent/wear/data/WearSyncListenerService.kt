package xyz.desent.wear.data

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import xyz.desent.data.wearsync.WearBunkerCodec
import xyz.desent.data.wearsync.WearCalendarCodec
import xyz.desent.data.wearsync.WearConfigCodec
import xyz.desent.data.wearsync.WearInboxCodec
import xyz.desent.data.wearsync.WearSyncPaths
import xyz.desent.wear.DeSentWearApplication

/**
 * Watch-side Data Layer listener: stores config, inbox, calendar, and bunker
 * pushes from the phone and exposes them reactively via the app container.
 */
class WearSyncListenerService : WearableListenerService() {

    companion object {
        private const val TAG = "WearSyncListener"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            when (event.dataItem.uri.path) {
                WearSyncPaths.CONFIG_PATH -> handleConfigEvent(event)
                WearSyncPaths.INBOX_PATH -> handleInboxEvent(event)
                WearSyncPaths.CALENDAR_PATH -> handleCalendarEvent(event)
                WearSyncPaths.BUNKER_PATH -> handleBunkerEvent(event)
            }
        }
    }

    private fun handleConfigEvent(event: DataEvent) {
        scope.launch {
            val container = DeSentWearApplication.appContainer
            when (event.type) {
                DataEvent.TYPE_CHANGED -> {
                    val bytes = event.dataItem.data ?: return@launch
                    val config = runCatching { WearConfigCodec.decode(bytes) }.getOrNull()
                        ?: return@launch
                    container.configStore.save(config)
                    container.reloadConfig()
                    Log.d(TAG, " Config stored (theme=${config.themeMode})")
                }
                DataEvent.TYPE_DELETED -> {
                    container.configStore.clear()
                    container.reloadConfig()
                    Log.d(TAG, "Config deleted")
                }
            }
        }
    }

    private fun handleInboxEvent(event: DataEvent) {
        scope.launch {
            val container = DeSentWearApplication.appContainer
            when (event.type) {
                DataEvent.TYPE_CHANGED -> {
                    val bytes = event.dataItem.data ?: return@launch
                    val inbox = runCatching { WearInboxCodec.decode(bytes) }.getOrNull()
                        ?: return@launch
                    container.inboxStore.save(inbox)
                    container.reloadInbox()
                    container.notifyNewMailIfFresh(inbox)
                    Log.d(TAG, " Inbox stored (emails=${inbox.emails.size}, spam=${inbox.spam.size})")
                }
                DataEvent.TYPE_DELETED -> {
                    container.inboxStore.clear()
                    container.reloadInbox()
                    Log.d(TAG, "Inbox deleted")
                }
            }
        }
    }

    private fun handleCalendarEvent(event: DataEvent) {
        scope.launch {
            val container = DeSentWearApplication.appContainer
            when (event.type) {
                DataEvent.TYPE_CHANGED -> {
                    val bytes = event.dataItem.data ?: return@launch
                    val calendar = runCatching { WearCalendarCodec.decode(bytes) }.getOrNull()
                        ?: return@launch
                    container.applyCalendarPush(calendar)
                    Log.d(TAG, " Calendar stored (events=${calendar.events.size})")
                }
                DataEvent.TYPE_DELETED -> {
                    container.calendarStore.clear()
                    container.reloadCalendar()
                    Log.d(TAG, "Calendar deleted")
                }
            }
        }
    }

    private fun handleBunkerEvent(event: DataEvent) {
        scope.launch {
            val container = DeSentWearApplication.appContainer
            when (event.type) {
                DataEvent.TYPE_CHANGED -> {
                    val bytes = event.dataItem.data ?: return@launch
                    val request = runCatching { WearBunkerCodec.decodeRequest(bytes) }.getOrNull()
                        ?: return@launch
                    container.applyBunkerPush(request)
                    Log.d(TAG, " Bunker state stored (requestId=${request.requestId?.take(12)})")
                }
                DataEvent.TYPE_DELETED -> {
                    container.clearBunker()
                    Log.d(TAG, "Bunker state deleted")
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
