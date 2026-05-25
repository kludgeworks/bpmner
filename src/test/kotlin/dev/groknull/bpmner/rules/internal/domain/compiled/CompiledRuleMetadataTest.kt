/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.compiled

import dev.groknull.bpmner.api.RepairKind
import dev.groknull.bpmner.api.RepairSafety
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class CompiledRuleMetadataTest {
    @Test
    fun `compiled rules expose llm model patch metadata`() {
        val rules =
            listOf(
                DanglingEdgeRule(),
                DefaultFlowRule(),
                DuplicateIdRule(),
                EventDefinitionRule(),
                RequiredEventsRule(),
                RequiredNameRule(),
                TaskPayloadRule(),
            )

        rules.forEach { rule ->
            assertEquals(rule.id, rule.metadata.id)
            assertEquals(RepairKind.LLM_MODEL_PATCH, rule.metadata.repair.kind)
            assertEquals(RepairSafety.LLM_ONLY, rule.metadata.repair.safety)
            assertFalse(rule.metadata.name.isBlank())
            assertFalse(rule.metadata.targetElements.isEmpty())
            assertFalse(rule.metadata.errorMessages.isEmpty())
        }
    }
}
