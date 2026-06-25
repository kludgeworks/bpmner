/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.browser.internal.adapter.outbound

import dev.groknull.bpmner.browser.InteractiveEnvironment
import org.jmolecules.architecture.hexagonal.SecondaryAdapter
import org.springframework.stereotype.Service
import java.awt.GraphicsEnvironment

@SecondaryAdapter
@Service
internal open class EnvInteractiveEnvironment(
    private val envGet: (String) -> String? = { System.getenv(it) },
    private val console: () -> Boolean = { System.console() != null },
    private val isHeadless: () -> Boolean = { GraphicsEnvironment.isHeadless() },
) : InteractiveEnvironment {

    override fun canOpenBrowser(): Boolean {
        // Check headless first to avoid premature AWT init
        if (isHeadless()) {
            return false
        }

        // CI environment check - presence of CI env var (not value) means non-interactive
        if (envGet("CI") != null) {
            return false
        }

        // Console must be present
        if (!console()) {
            return false
        }

        return true
    }
}
