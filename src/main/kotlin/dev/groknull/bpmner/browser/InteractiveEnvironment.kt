/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.browser

import org.jmolecules.architecture.hexagonal.SecondaryPort

/**
 * Secondary port for detecting whether the current environment is interactive
 * and capable of browser operation. Returns true only when all conditions hold:
 * - Not running in a CI environment (CI env var not set; any non-null value is treated as CI)
 * - Console is present (System.console() != null)
 * - GraphicsEnvironment is not headless (!isHeadless())
 *
 * This gate prevents blocking prompts or browser attempts in CI, test, and
 * headless server environments.
 */
@SecondaryPort
fun interface InteractiveEnvironment {
    fun canOpenBrowser(): Boolean
}
