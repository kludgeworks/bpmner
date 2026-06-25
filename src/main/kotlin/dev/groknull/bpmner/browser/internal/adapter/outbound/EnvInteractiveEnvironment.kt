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
    private val console: ConsoleAccessor = SystemConsoleAccessor,
    private val isHeadless: () -> Boolean = { GraphicsEnvironment.isHeadless() },
) : InteractiveEnvironment {

    override fun canOpenBrowser(): Boolean {
        // Check headless first to avoid premature AWT init
        if (isHeadless()) {
            return false
        }

        // CI environment check - CI=true means non-interactive
        val ciValue = envGet("CI")
        if (ciValue != null && ciValue.equals("true", ignoreCase = true)) {
            return false
        }

        // Console must be present
        if (console.getConsole() == null) {
            return false
        }

        return true
    }

    interface ConsoleAccessor {
        fun getConsole(): java.io.Console?
    }

    object SystemConsoleAccessor : ConsoleAccessor {
        override fun getConsole(): java.io.Console? = System.console()
    }
}
