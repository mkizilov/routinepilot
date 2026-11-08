package com.routinepilot.presentation.home

import java.util.UUID

data class HomeNavigation(
    val onEdit: (UUID) -> Unit,
    val onRun: (UUID) -> Unit,
    val onNew: () -> Unit,
)
