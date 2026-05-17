/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner

import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.core.importer.ImportOption.Predefined.DO_NOT_INCLUDE_TESTS
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

class BpmnerModulithTest {
    private val modules =
        ApplicationModules.of(
            BpmnerApplication::class.java,
            ImportOption { location ->
                DO_NOT_INCLUDE_TESTS.includes(location) &&
                    !location.contains("bpmner_tests_lib") &&
                    !location.contains("test_classes")
            },
        )

    @Test
    fun `verifies no illegal cross-module dependencies`() {
        modules.verify()
    }
}
