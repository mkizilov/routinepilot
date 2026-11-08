package com.routinepilot.domain.onboarding

/** The first-run introduction, in the order it is shown. The text for each page lives in the presentation layer. */
enum class OnboardingPage { WELCOME, ROUTINES, PRIVACY }

/**
 * The introduction shows only on a fresh install: it has not been finished or skipped, and there are no routines
 * yet. An existing user who upgrades already has routines, so the introduction is not shown to them.
 */
fun needsOnboarding(
    completed: Boolean,
    hasRoutines: Boolean,
): Boolean = !completed && !hasRoutines

/** The page after [page], or null on the last page. */
fun nextPage(page: OnboardingPage): OnboardingPage? = OnboardingPage.entries.getOrNull(page.ordinal + 1)

/** The page before [page], or null on the first page. */
fun previousPage(page: OnboardingPage): OnboardingPage? = OnboardingPage.entries.getOrNull(page.ordinal - 1)
