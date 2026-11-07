package dev.sushnt.journal.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.sushnt.journal.api.ApiError
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.util.concurrent.TimeUnit

/**
 * Android's WorkManager runs [SyncWorker] for us, even when the app is closed or the
 * phone restarted, and waits until there is internet. Edits made offline therefore
 * reach the server on their own.
 */
class AndroidBackgroundSync(context: Context) : BackgroundSync {
    private val work = WorkManager.getInstance(context.applicationContext)
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(online)
            .build()
        // KEEP: if it's already scheduled, leave the existing schedule alone.
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun syncSoon() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        work.enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancel() {
        work.cancelUniqueWork(PERIODIC)
        work.cancelUniqueWork(ONCE)
    }

    private companion object {
        const val PERIODIC = "journal-sync-periodic"
        const val ONCE = "journal-sync-once"
    }
}

/** One background sync. Uses the same SyncEngine as the screens (Koin), so the two never clash. */
class SyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params), KoinComponent {

    override suspend fun doWork(): Result {
        if (!get<SqliteTokenStore>().signedIn.value) return Result.success()
        return when (val result = get<SyncEngine>().sync()) {
            is SyncResult.Done -> Result.success()
            is SyncResult.Failed -> when (val e = result.error) {
                // Temporary problems: WorkManager tries again later, waiting longer each time.
                is ApiError.Network -> Result.retry()
                is ApiError.Server -> if (e.status >= 500 || e.status == 429) Result.retry() else Result.failure()
                ApiError.SessionExpired -> Result.failure()
            }
        }
    }
}
