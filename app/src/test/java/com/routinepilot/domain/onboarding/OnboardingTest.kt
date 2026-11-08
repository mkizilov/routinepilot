package com.routinepilot.domain.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingTest {
    @Test
    fun aFreshInstall_isShownTheIntroduction() {
        assertTrue(needsOnboarding(completed = false, hasRoutines = false))
    }

    @Test
    fun anIntroductionThatWasFinishedOrSkipped_isNotShownAgain() {
        assertFalse(needsOnboarding(completed = true, hasRoutines = false))
    }

    @Test
    fun anExistingUser_withRoutines_isNotShownTheIntroduction() {
        assertFalse(needsOnboarding(completed = false, hasRoutines = true))
    }

    @Test
    fun pagesRunInOrder_andStopAtBothEnds() {
        assertEquals(OnboardingPage.ROUTINES, nextPage(OnboardingPage.WELCOME))
        assertEquals(OnboardingPage.PRIVACY, nextPage(OnboardingPage.ROUTINES))
        assertNull(nextPage(OnboardingPage.PRIVACY))
        assertNull(previousPage(OnboardingPage.WELCOME))
        assertEquals(OnboardingPage.WELCOME, previousPage(OnboardingPage.ROUTINES))
    }
}
