/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.pipeline.internal.adapter.outbound.browser

/**
 * Outcome of attempting to open a file/URI in the default browser:
 * either opened, or failed-with-reason (covering both unsupported platforms and launch errors).
 */
sealed interface BrowserOpenOutcome {

    /** The browser successfully opened the target. */
    data object Opened : BrowserOpenOutcome

    /** Browser open was not attempted or did not succeed; [reason] explains why. */
    data class Failed(val reason: String) : BrowserOpenOutcome
}
