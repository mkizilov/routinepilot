package com.routinepilot.testing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.provides
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeContentTestRule
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertTrue

/**
 * Fails when a clickable element has no spoken label, so a screen reader can always say what it does. Checks the merged
 * semantics tree, the same tree TalkBack reads.
 */
fun ComposeContentTestRule.assertEveryClickableIsLabelled() {
    onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach { node ->
        val config = node.config
        val labelled = config.contains(SemanticsProperties.ContentDescription) ||
            config.contains(SemanticsProperties.Text) ||
            config.contains(SemanticsProperties.EditableText)
        assertTrue("Clickable element ${node.id} has no label", labelled)
    }
}

/** Renders [content] with the font scale multiplied, as a user with large text sees it. */
@Composable
fun WithFontScale(fontScale: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val scaled = Density(density.density, fontScale)
    CompositionLocalProvider(LocalDensity provides scaled, content = content)
}
