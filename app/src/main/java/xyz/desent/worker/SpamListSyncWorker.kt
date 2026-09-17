package xyz.desent.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import kotlinx.coroutines.flow.firstOrNull
import xyz.desent.di.AppContainer
import java.util.concurrent.TimeUnit

/**
 * Periodically fetches the DeSent spam blocklist (NIP-51 kind 30000) from the
 * trusted publisher and runs the one-shot first-run retroactive reclassification
 * pass if it hasn't run yet (see refs/SPAM_LIST_REFERENCE.md and
 * refs/SPAM_FILTER_REFERENCE.md).
 */
class SpamListSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val appContainer by lazy { AppContainer.getInstance(applicationContext) }

    override suspend fun doWork(): Result {
        return try {
            appContainer.syncSpamListUseCase()

            // Run the first-pass retroactive reclassification for the active account.
            val activeNpub = appContainer.preferencesManager.npubKey.firstOrNull()
            if (!activeNpub.isNullOrBlank()) {
                appContainer.reclassifyInboxUseCase.runIfNeeded(activeNpub)
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "spam_list_sync_worker"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<SpamListSyncWorker>(
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
