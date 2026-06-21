/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset.internal.domain

import dev.groknull.bpmner.bpmn.RepairKind
import dev.groknull.bpmner.bpmn.RepairMetadata
import dev.groknull.bpmner.bpmn.RepairSafety
import dev.groknull.bpmner.bpmn.RuleCategory
import dev.groknull.bpmner.ruleset.internal.domain.nlp.testBpmnNlp
import dev.groknull.bpmner.ruleset.internal.domain.primitives.PresenceCheckConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

internal class RuleDslTest {
    private val nlp = testBpmnNlp()

    @Test
    fun `derives ids from every category short code`() {
        val ids = RuleCategory.entries.associateWith { category ->
            primitiveRule(
                name = "Sample Rule",
                category = category,
                intent = "Detect a sample condition.",
                forModellers = "Fix the sample condition.",
                forAI = "Do not emit the sample condition.",
                targetElements = listOf("bpmn:Task"),
                errorMessages = mapOf("default" to "Sample violation."),
                check = PresenceCheckConfig,
                nlp = nlp,
            ).id
        }

        assertEquals("act-sample-rule", ids[RuleCategory.Activity])
        assertEquals("assoc-sample-rule", ids[RuleCategory.Association])
        assertEquals("art-sample-rule", ids[RuleCategory.Artifact])
        assertEquals("data-sample-rule", ids[RuleCategory.Data])
        assertEquals("evt-sample-rule", ids[RuleCategory.Event])
        assertEquals("flow-sample-rule", ids[RuleCategory.Flow])
        assertEquals("gtw-sample-rule", ids[RuleCategory.Gateway])
        assertEquals("gen-sample-rule", ids[RuleCategory.General])
        assertEquals("lane-sample-rule", ids[RuleCategory.Lane])
        assertEquals("msg-sample-rule", ids[RuleCategory.Message])
        assertEquals("name-sample-rule", ids[RuleCategory.Name])
        assertEquals("pool-sample-rule", ids[RuleCategory.Pool])
        assertEquals("def-sample-rule", ids[RuleCategory.Definition])
    }

    @Test
    fun `primitive rule carries optional metadata`() {
        val rule = primitiveRule(
            name = "Deprecated Primitive",
            category = RuleCategory.Definition,
            intent = "Detect legacy structure.",
            forModellers = "Use the replacement structure.",
            forAI = "Prefer the replacement structure.",
            targetElements = listOf("bpmn:Process"),
            errorMessages = mapOf("default" to "Legacy structure."),
            check = PresenceCheckConfig,
            nlp = nlp,
            aliases = listOf("old-primitive"),
            repair = RepairMetadata(
                kind = RepairKind.LOCAL_MODEL_FIX,
                safety = RepairSafety.SAFE_AUTOMATIC,
                handler = "replaceLegacy",
                replacementMap = mapOf("old" to "new"),
            ),
            deprecated = true,
            replacedBy = listOf("def-replacement"),
            deprecationReason = "Replacement rule is stricter.",
        )

        assertIs<DeterministicRule>(rule)
        assertEquals("def-deprecated-primitive", rule.id)
        assertEquals(listOf("old-primitive"), rule.metadata.aliases)
        assertEquals(mapOf("old" to "new"), rule.metadata.repair.replacementMap)
        assertEquals(true, rule.metadata.deprecated)
        assertEquals(listOf("def-replacement"), rule.metadata.replacedBy)
        assertEquals("Replacement rule is stricter.", rule.metadata.deprecationReason)
    }

    @Test
    fun `composite rule records sub checks without DSL target type narrowing`() {
        val rule = compositeRule(
            name = "Composite Sample",
            category = RuleCategory.Event,
            intent = "Detect event structure issues.",
            forModellers = "Repair event structure issues.",
            forAI = "Generate valid event structure.",
            targetElements = listOf("bpmn:Event"),
            errorMessages = mapOf("missing" to "Event is missing."),
            nlp = nlp,
        ) {
            sub("missing", PresenceCheckConfig)
        }

        val composite = assertIs<CompositeRule>(rule)
        assertEquals("evt-composite-sample", composite.id)
        assertEquals(emptyList(), composite.config.targetTypes)
        assertEquals("missing", composite.config.subChecks.single().diagnosticCode)
    }

    @Test
    fun `llm rule is metadata only with defaults`() {
        val spec = llmRule(
            name = "Review Narrative",
            category = RuleCategory.General,
            intent = "Review process narrative.",
            forModellers = "Keep narrative clear.",
            forAI = "Check narrative clarity.",
            targetElements = listOf("bpmn:Process"),
            errorMessages = mapOf("default" to "Narrative issue."),
        )

        assertEquals("gen-review-narrative", spec.metadata.id)
        assertFalse(spec.metadata.deprecated)
        assertEquals(emptyList(), spec.metadata.replacedBy)
        assertNull(spec.metadata.deprecationReason)
    }
}
