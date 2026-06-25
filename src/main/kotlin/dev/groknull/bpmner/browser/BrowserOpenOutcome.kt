/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.browser

/**
 * Outcome of attempting to open a file/URI in the default browser.
 * Distinguishes between opened, unsupported, and failed-with-reason outcomes.
 */
sealed interface BrowserOpenOutcome {

    /** The browser successfully opened the target. */
    data object Opened : BrowserOpenOutcome

    /** Browser open is not supported on this platform or environment. */
    data class Unsupported(val reason: String) : BrowserOpenOutcome

    /** Browser open was attempted but failed. */
    data class Failed(val reason: String) : BrowserOpenOutcome
}
