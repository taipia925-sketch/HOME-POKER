package it.homepoker.app

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

/** Aggiornamento del widget: ogni mezz'ora in automatico, oppure subito quando serve. */
class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        val r = Store.fetch(c)
        Store.setLoading(c, false)
        PokerWidget.renderAll(c)
        return if (r == Store.Result.Offline && runAttemptCount < 3) Result.retry() else Result.success()
    }
}

object Refresh {
    private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(c: Context) {
        val req = PeriodicWorkRequestBuilder<RefreshWorker>(30, TimeUnit.MINUTES)
            .setConstraints(net)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("hp-periodic", ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun now(c: Context) {
        Store.setLoading(c, true)
        val req = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setConstraints(net)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 20, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(c).enqueueUniqueWork("hp-now", ExistingWorkPolicy.REPLACE, req)
    }

    fun cancel(c: Context) {
        WorkManager.getInstance(c).cancelUniqueWork("hp-periodic")
        WorkManager.getInstance(c).cancelUniqueWork("hp-now")
    }
}
