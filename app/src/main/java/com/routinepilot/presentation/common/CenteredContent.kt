package com.routinepilot.presentation.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The widest a screen's content gets. Phones are narrower, so they use the full width. Tablets keep lines readable. */
private val MAX_CONTENT_WIDTH = 640.dp

/** Places a screen's content at the top, inside the scaffold padding, and no wider than [MAX_CONTENT_WIDTH]. */
@Composable
fun CenteredContent(padding: PaddingValues, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = MAX_CONTENT_WIDTH).fillMaxSize()) { content() }
    }
}
