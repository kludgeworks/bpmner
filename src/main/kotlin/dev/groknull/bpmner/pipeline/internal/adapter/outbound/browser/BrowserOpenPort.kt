/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.pipeline.internal.adapter.outbound.browser

import org.jmolecules.architecture.onion.simplified.ApplicationRing
import java.nio.file.Path

/**
 * Secondary port for opening a file/URI in the OS default browser.
 * Returns an explicit [BrowserOpenOutcome] rather than throwing.
 */
@ApplicationRing
fun interface BrowserOpenPort {
    fun open(target: Path): BrowserOpenOutcome
}
