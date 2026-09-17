package xyz.desent.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import xyz.desent.di.AppContainer
import java.util.concurrent.TimeUnit

/**
 * Periodic safety-net for the NIP-78 spam-token snapshot
 * (`desent:spam-tokens:*`). The primary sync paths are:
 *  - config  → [xyz.desent.data.spam.SpamSettingsSyncCoordinator] (reactive, debounced)
 *  - tokens  → the on-background trigger in `DeSentApplication` (user just left
 *              the app, so inbound snapshots have had time to merge locally).
 *
 * This worker covers the case where a device is rarely foregrounded: it
 * restores the identity, opens the own-private-storage subscription (so remote
 * config/tokens merge in), and publishes a token snapshot when the local
 * corpus is dirty. It is a no-op (skips the relay round-trip) when there is no
 * delta since the last snapshot.
 */
class SpamSettingsSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val appContainer by lazy { AppContainer.getInstance(applicationContext) }

    override suspend fun doWork(): Result {
        // Restore identity (idempotent). Returns failure when logged out, in
        // which case there's nothing to sync — exit cleanly rather than retry.
        val restored = runCatching { appContainer.nostrRepository.restoreIdentity() }
            .getOrNull()?.getOrNull()
        if (restored == null) {
            Log.d(TAG, "No active identity; nothing to sync")
            return Result.success()
        }

        // Ensure relays are connected (idempotent) so the publish can land.
        runCatching { appContainer.relayRepository.connectToPersistentRelays() }

        return try {
            // restoreIdentity() already opened the own-private-storage
            // subscription; publish a token snapshot if the local corpus is dirty.
            val result = appContainer.privateStorageRepository.snapshotAndPublishTokens(force = false)
            if (result.isFailure) {
                Log.w(TAG, "Token snapshot failed: ${result.exceptionOrNull()?.message}")
                Result.retry()
            } else {
                Result.success()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Spam settings sync failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "SpamSettingsSyncWorker"
        private const val WORK_NAME = "spam_settings_sync_worker"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<SpamSettingsSyncWorker>(
                24, TimeUnit.HOURS
            ).setConstraints(constraints).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
