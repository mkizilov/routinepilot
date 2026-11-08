package com.routinepilot.data.sync

import com.routinepilot.data.mapping.toDomain
import com.routinepilot.domain.sync.ObjectKey
import com.routinepilot.domain.sync.OfflineReplica
import com.routinepilot.domain.sync.OutboxStore
import com.routinepilot.domain.sync.SyncType
import com.routinepilot.infrastructure.database.RoutinePilotDatabase
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Instant
import javax.inject.Inject

/**
 * Queues records that were created before sync existed, so the first sign-in uploads them. A record is queued only when
 * neither the server nor the outbox knows it, so running this again changes nothing.
 */
class RecordSeeder
    @Inject
    constructor(
        private val database: RoutinePilotDatabase,
        private val outbox: OutboxStore,
        private val clock: Clock,
    ) {
        suspend fun seedUntrackedRecords() {
            val replica = OfflineReplica(outbox)
            val now = Instant.now(clock).toEpochMilli()
            for (routine in database.routineDao().observeActive().first()) {
                seed(
                    ObjectKey(SyncType.ROUTINE, routine.id),
                    SyncFieldMapping.routine(routine.toDomain()),
                    replica,
                    now,
                )
                for (step in database.routineStepDao().activeSnapshot(routine.id)) {
                    val domain = step.toDomain()
                    seed(ObjectKey(SyncType.ROUTINE_STEP, step.id), SyncFieldMapping.step(domain), replica, now)
                }
            }
            for (task in database.taskDao().observeActive().first()) {
                seed(ObjectKey(SyncType.TASK, task.id), SyncFieldMapping.task(task.toDomain()), replica, now)
            }
            for (schedule in database.scheduleDao().observeActive().first()) {
                val key = ObjectKey(SyncType.SCHEDULE, schedule.id)
                seed(key, SyncFieldMapping.schedule(schedule.toDomain()), replica, now)
            }
        }

        private suspend fun seed(
            key: ObjectKey,
            fields: Map<String, String?>,
            replica: OfflineReplica,
            nowMillis: Long,
        ) {
            if (outbox.base(key) != null || outbox.pending(key).isNotEmpty()) return
            replica.record(key, before = null, after = fields, nowMillis = nowMillis)
        }
    }
