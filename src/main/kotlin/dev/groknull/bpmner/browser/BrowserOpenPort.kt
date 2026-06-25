/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.browser

import org.jmolecules.architecture.hexagonal.SecondaryPort
import java.nio.file.Path

/**
 * Secondary port for opening a file/URI in the OS default browser.
 * Returns an explicit [BrowserOpenOutcome] rather than throwing.
 */
@SecondaryPort
fun interface BrowserOpenPort {
    fun open(target: Path): BrowserOpenOutcome
}
