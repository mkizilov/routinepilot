package com.routinepilot.infrastructure.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.routinepilot.domain.sync.CycleResult
import com.routinepilot.domain.sync.SyncEngine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * One sync pass. A pass that is refused for sign-in reasons succeeds without retrying, because retrying cannot fix it.
 * The
 * next sign-in schedules a pass. A pass that could not reach the server is retried by WorkManager's backoff.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val engine: SyncEngine,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = when (engine.runCycle()) {
        CycleResult.Done, CycleResult.NotSignedIn, CycleResult.NeedsSignIn -> Result.success()
        CycleResult.Retry -> Result.retry()
    }
}
