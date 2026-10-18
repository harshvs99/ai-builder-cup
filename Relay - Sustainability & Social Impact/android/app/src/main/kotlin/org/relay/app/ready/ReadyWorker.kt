package org.relay.app.ready

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.relay.app.MeshRepository
import org.relay.core.ready.RefreshTrigger
import java.util.concurrent.TimeUnit

/** Best-effort background refresh of the Ready pack: every 6 h, only with a network (contract section 3.3). */
class ReadyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        MeshRepository.init(applicationContext)
        ReadyRepository.refresh(applicationContext, RefreshTrigger.BACKGROUND)
        return Result.success() // best effort: a failed refresh waits for the next period, it is not retried in a loop
    }

    companion object {
        private const val NAME = "ready-refresh"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<ReadyWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(ctx.applicationContext).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
