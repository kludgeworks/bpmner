/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner

import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.core.importer.ImportOption.Predefined.DO_NOT_INCLUDE_TESTS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules
import org.springframework.modulith.core.VerificationOptions

class BpmnerModulithTest {
    private val modules =
        ApplicationModules.of(
            BpmnerApplication::class.java,
            ImportOption { location ->
                DO_NOT_INCLUDE_TESTS.includes(location) &&
                    !location.contains("bpmner_tests_lib") &&
                    !location.contains("test-classes") &&
                    !location.contains("test_classes") &&
                    !location.contains("/test/") &&
                    !location.contains("/src/test")
            },
        )

    @Test
    fun `verifies expected modules are detected`() {
        val moduleNames = modules.map { it.name }.toSet()
        val expectedModules =
            listOf(
                "alignment",
                "config",
                "contract",
                "core",
                "generation",
                "layout",
                "observability",
                "readiness",
                "repair",
                "shell",
                "validation",
                "web",
            )
        assertEquals(
            expectedModules.toSet(),
            moduleNames,
        )
    }

    @Test
    fun `verifies no illegal cross-module dependencies`() {
        modules.verify(VerificationOptions.defaults().withoutAdditionalVerifications())
    }
}
