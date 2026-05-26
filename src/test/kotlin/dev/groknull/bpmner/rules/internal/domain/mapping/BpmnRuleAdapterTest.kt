/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.mapping

import dev.groknull.bpmner.api.RepairKind
import dev.groknull.bpmner.api.RepairSafety
import dev.groknull.bpmner.api.RuleSeverity
import dev.groknull.bpmner.pkl.generated.RuleCategory
import dev.groknull.bpmner.rules.internal.domain.primitives.PropertyPatternCheckConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import dev.groknull.bpmner.pkl.generated.BpmnRule as PklBpmnRule
import dev.groknull.bpmner.pkl.generated.CheckPrimitive as PklCheckPrim

internal class BpmnRuleAdapterTest {

    @Test
    fun `rule without checkPrimitive is skipped`() {
        val generated = newGenerated(checkPrimitive = null, checkConfig = null)

        assertNull(BpmnRuleAdapter.adapt(generated))
    }

    @Test
    fun `rule with checkPrimitive produces metadata and mapped check`() {
        val generated = newGenerated(
            checkPrimitive = PklCheckPrim.Primitive.PROPERTY_PATTERN_CHECK,
            checkConfig = PklCheckPrim.PropertyPatternCheck("name", "^[A-Z].*$", "sentence case"),
        )

        val adapted = BpmnRuleAdapter.adapt(generated)
            ?: error("adapter returned null for rule with checkPrimitive set")

        assertEquals("act-test-rule", adapted.metadata.id)
        assertEquals(RuleSeverity.WARNING, adapted.metadata.severity)
        assertEquals(RepairKind.LOCAL_MODEL_FIX, adapted.metadata.repair.kind)
        assertEquals(RepairSafety.SAFE_AUTOMATIC, adapted.metadata.repair.safety)
        assertEquals("fixSentenceCase", adapted.metadata.repair.handler)
        // checkPrimitive carries the Pkl-form name, not the SCREAMING_SNAKE enum name.
        assertEquals("PropertyPatternCheck", adapted.metadata.checkPrimitive)
        assertIs<MappedCheck.Deterministic>(adapted.mappedCheck)
        assertEquals(
            PropertyPatternCheckConfig("name", "^[A-Z].*$", "sentence case"),
            (adapted.mappedCheck as MappedCheck.Deterministic).config,
        )
    }

    @Test
    fun `rule with checkPrimitive but no checkConfig fails loudly`() {
        val generated = newGenerated(
            checkPrimitive = PklCheckPrim.Primitive.PROPERTY_PATTERN_CHECK,
            checkConfig = null,
        )

        val failure = runCatching { BpmnRuleAdapter.adapt(generated) }.exceptionOrNull()
        assertIs<IllegalStateException>(failure)
        assertEquals(true, failure.message?.contains("checkConfig"))
    }

    private fun newGenerated(
        checkPrimitive: PklCheckPrim.Primitive?,
        checkConfig: PklCheckPrim.CheckConfig?,
    ): PklBpmnRule {
        // Constructor arg order matches BpmnRule.pkl: name, category, slug, id, intent,
        // forModellers, forAI, targetElements, severity, errorMessages, staticConfig,
        // checkPrimitive, checkConfig, repair, hasTsImplementation, aliases, deprecated,
        // replacedBy, deprecationReason.
        val repair = PklBpmnRule.Repair(
            PklBpmnRule.RepairKind.LOCAL_MODEL_FIX,
            PklBpmnRule.RepairSafety.SAFE_AUTOMATIC,
            "fixSentenceCase",
            null,
            null,
            null,
        )
        return PklBpmnRule(
            "Test rule",
            RuleCategory.Category("Activity", "act"),
            "test-rule",
            "act-test-rule",
            "intent",
            "for modellers",
            "for AI",
            listOf("bpmn:Task"),
            PklBpmnRule.Severity.WARNING,
            mapOf("default" to "violation"),
            null,
            checkPrimitive,
            checkConfig,
            repair,
            false,
            emptyList(),
            false,
            emptyList(),
            null,
        )
    }
}
