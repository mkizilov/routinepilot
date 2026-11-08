package com.routinepilot.infrastructure.notifications

import com.routinepilot.data.execution.RoutineRunCoordinator
import com.routinepilot.domain.execution.RunEvent
import com.routinepilot.infrastructure.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Turns run transitions into notifications for the whole process, whether or not a screen is showing. */
@Singleton
class RunEventNotifier
    @Inject
    constructor(
        private val coordinator: RoutineRunCoordinator,
        private val presenter: RunNotificationPresenter,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        private var started = false

        fun start() {
            if (started) return
            started = true
            scope.launch {
                coordinator.events.collect { event ->
                    when (event) {
                        is RunEvent.StepCompleted -> presenter.showStepCompleted(event)
                        is RunEvent.RoutineFinished -> presenter.showRoutineFinished(event)
                    }
                }
            }
        }
    }
