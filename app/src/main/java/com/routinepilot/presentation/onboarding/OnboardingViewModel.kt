package com.routinepilot.presentation.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.routinepilot.data.onboarding.OnboardingStore
import com.routinepilot.domain.onboarding.needsOnboarding
import com.routinepilot.domain.repository.RoutineRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val store: OnboardingStore,
    routines: RoutineRepository,
) : ViewModel() {
    /** Null until the stored flag and the routine list have loaded, so the start screen is chosen only once. */
    val introNeeded: StateFlow<Boolean?> = combine(store.completed, routines.observeRoutines()) { completed, list ->
        needsOnboarding(completed, list.isNotEmpty())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun finish() {
        viewModelScope.launch { store.markCompleted() }
    }
}
