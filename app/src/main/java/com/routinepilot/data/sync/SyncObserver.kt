package com.routinepilot.data.sync

import com.routinepilot.infrastructure.database.RoutinePilotDatabase
import com.routinepilot.infrastructure.di.ApplicationScope
import com.routinepilot.infrastructure.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val DEBOUNCE_MILLIS = 1_000L

/**
 * Schedules a sync whenever the queued-change count changes while the device is signed in. A burst of edits syncs once.
 * The
 * periodic job is started for a signed-in account, so changes made on another device arrive without a local edit.
 */
@Singleton
class SyncObserver @Inject constructor(
    private val database: RoutinePilotDatabase,
    private val account: SyncAccountStore,
    private val scheduler: SyncScheduler,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            if (account.accountSignedIn()) scheduler.startPeriodic()
            database.syncOutboxDao().observePendingCount()
                .distinctUntilChanged()
                .debounce(DEBOUNCE_MILLIS)
                .collect { if (account.accountSignedIn()) scheduler.requestNow() }
        }
    }
}
