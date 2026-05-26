/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.validation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RuleCatalogMetadataTest {
    @Test
    fun `BpmnRuleMetadata rejects empty error messages at construction time`() {
        val exception =
            assertThrows<IllegalArgumentException> {
                BpmnRuleMetadata(
                    id = "test-empty-errors",
                    name = "Empty Errors",
                    category = RuleCategoryMetadata(name = "test", shortCode = "T"),
                    slug = "empty-errors",
                    intent = "test",
                    forModellers = "test",
                    forAI = "test",
                    targetElements = emptyList(),
                    severity = "warning",
                    errorMessages = emptyMap(),
                    staticConfig = null,
                    hasTsImplementation = false,
                    aliases = emptyList(),
                    deprecated = false,
                    replacedBy = emptyList(),
                    deprecationReason = null,
                )
            }

        assertEquals("Rule 'test-empty-errors' must define at least one error message", exception.message)
    }
}
