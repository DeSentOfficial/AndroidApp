package xyz.desent

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import coil.Coil
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import xyz.desent.data.attachment.DesentBlobAuthInterceptor
import xyz.desent.di.AppContainer

class DeSentApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        lateinit var appContainer: AppContainer
            private set

        /**
         * Minimum spacing between blocklist (NIP-51) fetches at process start.
         * The daily worker keeps the list fresh; a cold open inside this
         * window skips the fetch (the stored manifest still applies).
         */
        private const val SPAM_LIST_MIN_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }

    // Expose for widget access
    val widgetDataHelper
        get() = appContainer.widgetDataHelper

    override fun onCreate() {
        super.onCreate()

        // Enable animated GIF support + transparent auth for DeSent blob URLs
        // so remote images render via Coil (signed kind 22242 header).
        val coilClient = OkHttpClient.Builder()
            .addInterceptor(DesentBlobAuthInterceptor { DeSentApplication.appContainer.secureKeyManager })
            .build()

        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .okHttpClient(coilClient)
                // Avatars/favicons are often served without cache headers;
                // persist image bytes in the disk cache regardless (remote
                // blob/badge URLs are content-addressed, so no staleness).
                .respectCacheHeaders(false)
                .components {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        add(ImageDecoderDecoder.Factory())
                    } else {
                        add(GifDecoder.Factory())
                    }
                }
                .build()
        )

        appContainer = AppContainer.getInstance(this)
        appContainer.nostrEventProcessor.startProcessing()

        // Start posting system-tray notifications for emails / followers.
        // Runs on the application scope for the life of the process.
        appContainer.systemNotificationDispatcher.start()

        // If the user previously opted into the background service, restart it
        // (e.g. after a process restart or device reboot while the app was live).
        applicationScope.launch {
            appContainer.backgroundServiceController.syncFromPreference()
        }

        // Keep the watch's config (theme mode) fresh: initial push + pushes
        // whenever the theme preference changes.
        applicationScope.launch {
            runCatching { appContainer.wearSyncManager.pushConfig() }
                .onFailure { android.util.Log.w("DeSentApplication", "Wear config push at launch failed: ${it.message}") }
        }
        appContainer.startWearConfigSync()

        // Keep the watch's inbox snapshot fresh: initial push of the
        // decrypted reading copies + debounced re-pushes on inbox changes.
        applicationScope.launch {
            runCatching { appContainer.wearSyncManager.pushInbox() }
                .onFailure { android.util.Log.w("DeSentApplication", "Wear inbox push at launch failed: ${it.message}") }
        }
        appContainer.startWearInboxSync()

        // Keep the watch's calendar fresh: initial push of the expanded
        // today+14d snapshot + debounced re-pushes on calendar/contacts changes.
        applicationScope.launch {
            runCatching { appContainer.wearSyncManager.pushCalendar() }
                .onFailure { android.util.Log.w("DeSentApplication", "Wear calendar push at launch failed: ${it.message}") }
        }
        appContainer.startWearCalendarSync()

        // Mirror the bunker's pending-prompt slot to the watch (NIP-46
        // accept/deny on the wrist). The launch push also clears any stale
        // request left on the watch by a previous process.
        applicationScope.launch {
            runCatching { appContainer.wearSyncManager.pushBunkerState() }
                .onFailure { android.util.Log.w("DeSentApplication", "Wear bunker push at launch failed: ${it.message}") }
        }
        appContainer.startWearBunkerSync()

        // Connect DeSent relays immediately — no auth required to connect
        applicationScope.launch {
            appContainer.relayRepository.connectToPersistentRelays()
        }

        // Reconnect relays + re-subscribe DMs when the network changes
        // (wifi↔cellular, regain after dropout). Without this a network
        // transition leaves the WebSocket half-open and live delivery stalls.
        registerNetworkCallback()

        // Spam filter: schedule the daily blocklist (NIP-51) sync worker and
        // run a sync + first-run retroactive reclassification for the active
        // account (see refs/SPAM_FILTER_REFERENCE.md). All work is best-effort;
        // the worker retries on failure. The blocklist fetch itself is gated
        // to once per [SPAM_LIST_MIN_INTERVAL_MS] — before the sync-cursor
        // work this re-downloaded the entire blocklist event on every process
        // start even when the version hadn't changed.
        xyz.desent.worker.SpamListSyncWorker.schedule(this)
        applicationScope.launch {
            runCatching {
                val lastSyncAt = appContainer.preferencesManager.spamListLastSyncAt.firstOrNull() ?: 0L
                if (System.currentTimeMillis() - lastSyncAt > SPAM_LIST_MIN_INTERVAL_MS) {
                    appContainer.syncSpamListUseCase()
                }
                val npub = appContainer.preferencesManager.npubKey.firstOrNull()
                if (!npub.isNullOrBlank()) {
                    appContainer.reclassifyInboxUseCase.runIfNeeded(npub)
                }
            }
        }

        // Spam policy cross-device sync (NIP-78 encrypted kind 30078 on the
        // email relay — see SpamSettingsSyncCoordinator). Config publishes
        // reactively (debounced); the token snapshot runs daily + on background.
        xyz.desent.worker.SpamSettingsSyncWorker.schedule(this)
        appContainer.spamSettingsSyncCoordinator.start()
        registerSpamTokensBackgroundSync()
    }

    /**
     * Publish a spam-token snapshot when the app moves to the background. The
     * user has just left the app, so inbound snapshots have had time to merge
     * into the local corpus — making this the most reliable token-sync trigger.
     * No-op when the corpus isn't dirty. Config is handled separately by the
     * reactive [xyz.desent.data.spam.SpamSettingsSyncCoordinator].
     *
     * Also drains the debounced mail-state overlay flush (mail folders +
     * synced read state) so filed/read-but-not-yet-published shards don't wait
     * for the next app open.
     */
    private fun registerSpamTokensBackgroundSync() {
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                    applicationScope.launch {
                        runCatching {
                            appContainer.privateStorageRepository.snapshotAndPublishTokens(force = false)
                        }.onFailure {
                            android.util.Log.w("DeSentApplication", "Background token snapshot failed: ${it.message}")
                        }
                        runCatching {
                            appContainer.mailFolderRepository.flush(force = false)
                        }.onFailure {
                            android.util.Log.w("DeSentApplication", "Background mail-state flush failed: ${it.message}")
                        }
                    }
                }
            }
        )
    }

    /**
     * Force persistent relays to reconnect and gift-wrap subscriptions to re-register
     * whenever the OS reports a (re)gained validated network. Handles wifi↔cellular
     * handoff and dropouts where the old WebSocket is left half-open.
     */
    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = android.net.NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: android.net.Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    applicationScope.launch {
                        runCatching { appContainer.relayRepository.connectToPersistentRelays() }
                        runCatching { appContainer.nostrRepository.subscribeToGiftWraps() }
                    }
                }
            }
        })
    }
}
