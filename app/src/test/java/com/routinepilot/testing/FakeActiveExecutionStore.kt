package com.routinepilot.testing

import com.routinepilot.domain.execution.ActiveExecution
import com.routinepilot.domain.execution.ActiveExecutionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeActiveExecutionStore(initial: ActiveExecution? = null) : ActiveExecutionStore {
    val current = MutableStateFlow(initial)

    override fun observe(): Flow<ActiveExecution?> = current

    override suspend fun save(execution: ActiveExecution) {
        current.value = execution
    }

    override suspend fun clear() {
        current.value = null
    }
}
