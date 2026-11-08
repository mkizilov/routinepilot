package com.routinepilot.infrastructure.widget

import android.content.Context
import com.routinepilot.data.execution.RoutineRunCoordinator
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Glance widgets and their action callbacks are created by the framework, so they cannot use constructor injection.
 * This entry point gives them the application's singletons.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun widgetStateLoader(): WidgetStateLoader

    fun runCoordinator(): RoutineRunCoordinator
}

internal fun Context.widgetEntryPoint(): WidgetEntryPoint =
    EntryPointAccessors.fromApplication(applicationContext, WidgetEntryPoint::class.java)
