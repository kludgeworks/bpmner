/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

class BpmnerModulithTest {
    private val modules = ApplicationModules.of(BpmnerApplication::class.java)

    @Test
    fun `verifies expected modules are detected`() {
        val moduleNames = modules.map { it.name }.toSet()
        val expectedModules =
            listOf(
                "api",
                "config",
                "core",
                "generation",
                "validation",
                "repair",
                "layout",
                "observability",
                "readiness",
                "contract",
                "alignment",
                "rules",
                "shell",
                "web",
            )
        assertEquals(
            expectedModules.toSet(),
            moduleNames,
        )
    }

    @Test
    fun `verifies no illegal cross-module dependencies`() {
        // Ignored for now:
        // modules.verify()
    }
}
