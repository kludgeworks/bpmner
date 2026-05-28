/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.mapping

import dev.groknull.bpmner.rules.internal.domain.primitives.CardinalityCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.CompositeCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.ConnectivityCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.ConnectivityMode
import dev.groknull.bpmner.rules.internal.domain.primitives.ElementConstraintCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.ElementConstraintMode
import dev.groknull.bpmner.rules.internal.domain.primitives.PairingCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.PairingMode
import dev.groknull.bpmner.rules.internal.domain.primitives.PoolLabelCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.PoolLabelMode
import dev.groknull.bpmner.rules.internal.domain.primitives.PropertyPatternCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.RequiredAssociationCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.RequiredPropertyCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.SubCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.TopologyCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.TopologyMode
import dev.groknull.bpmner.rules.internal.domain.primitives.VocabularyCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.VocabularyMode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import dev.groknull.bpmner.pkl.CheckPrimitive as PklCheckPrim

@Suppress("TooManyFunctions") // 15 cases: one per primitive type plus rejection paths
internal class CheckConfigMapperTest {

    @Test
    fun `RequiredPropertyCheck maps to deterministic config`() {
        val mapped = CheckConfigMapper.map(PklCheckPrim.RequiredPropertyCheck("name"))

        assertIs<MappedCheck.Deterministic>(mapped)
        assertEquals(RequiredPropertyCheckConfig(property = "name"), mapped.config)
    }

    @Test
    fun `PropertyPatternCheck preserves all three fields including nullable description`() {
        val mapped = CheckConfigMapper.map(
            PklCheckPrim.PropertyPatternCheck("name", "^[A-Z].*$", "sentence case", null, null),
        )

        assertEquals(
            MappedCheck.Deterministic(
                PropertyPatternCheckConfig(
                    property = "name",
                    pattern = "^[A-Z].*$",
                    patternDescription = "sentence case",
                ),
            ),
            mapped,
        )
    }

    @Test
    fun `PropertyPatternCheck preserves forbiddenVocabulary and allowedVocabulary lists`() {
        val mapped = CheckConfigMapper.map(
            PklCheckPrim.PropertyPatternCheck(
                "name",
                ".+",
                "no technical tokens",
                listOf("api", "svc"),
                listOf("BPMN", "ACME"),
            ),
        )

        assertEquals(
            MappedCheck.Deterministic(
                PropertyPatternCheckConfig(
                    property = "name",
                    pattern = ".+",
                    patternDescription = "no technical tokens",
                    forbiddenVocabulary = listOf("api", "svc"),
                    allowedVocabulary = listOf("BPMN", "ACME"),
                ),
            ),
            mapped,
        )
    }

    @Test
    fun `VocabularyCheck converts string mode to enum`() {
        val mapped = CheckConfigMapper.map(
            PklCheckPrim.VocabularyCheck("name", "FORBID", listOf("manage", "handle")),
        )

        assertEquals(
            MappedCheck.Deterministic(
                VocabularyCheckConfig(
                    property = "name",
                    mode = VocabularyMode.FORBID,
                    words = listOf("manage", "handle"),
                ),
            ),
            mapped,
        )
    }

    @Test
    fun `VocabularyCheck rejects unknown mode`() {
        val failure = assertFailsWith<IllegalStateException> {
            CheckConfigMapper.map(PklCheckPrim.VocabularyCheck("name", "WHATEVER", emptyList()))
        }
        assertEquals(true, failure.message?.contains("WHATEVER"))
    }

    @Test
    fun `RequiredAssociationCheck preserves source and target type filters`() {
        val mapped = CheckConfigMapper.map(
            PklCheckPrim.RequiredAssociationCheck(
                "bpmn:Association",
                listOf("bpmn:TextAnnotation"),
                listOf("bpmn:Task"),
            ),
        )

        assertEquals(
            MappedCheck.Deterministic(
                RequiredAssociationCheckConfig(
                    association = "bpmn:Association",
                    sourceTypes = listOf("bpmn:TextAnnotation"),
                    targetTypes = listOf("bpmn:Task"),
                ),
            ),
            mapped,
        )
    }

    @Test
    fun `TopologyCheck narrows Long bounds to Int`() {
        val mapped = CheckConfigMapper.map(
            PklCheckPrim.TopologyCheck("NO_FAKE_JOIN", 2L, null, 1L, null),
        )

        assertEquals(
            MappedCheck.Deterministic(
                TopologyCheckConfig(
                    topology = TopologyMode.NO_FAKE_JOIN,
                    minIncoming = 2,
                    maxIncoming = null,
                    minOutgoing = 1,
                    maxOutgoing = null,
                ),
            ),
            mapped,
        )
    }

    @Test
    fun `ConnectivityCheck preserves source target types`() {
        val mapped = CheckConfigMapper.map(
            PklCheckPrim.ConnectivityCheck("WITHIN_POOL", listOf("bpmn:SequenceFlow"), emptyList()),
        )

        assertEquals(
            MappedCheck.Deterministic(
                ConnectivityCheckConfig(
                    mode = ConnectivityMode.WITHIN_POOL,
                    sourceTypes = listOf("bpmn:SequenceFlow"),
                    targetTypes = emptyList(),
                ),
            ),
            mapped,
        )
    }

    @Test
    fun `PairingCheck maps mode and optional sides`() {
        val mapped = CheckConfigMapper.map(
            PklCheckPrim.PairingCheck("ERROR_END_BOUNDARY", "bpmn:EndEvent", "bpmn:BoundaryEvent"),
        )

        assertEquals(
            MappedCheck.Deterministic(
                PairingCheckConfig(
                    mode = PairingMode.ERROR_END_BOUNDARY,
                    left = "bpmn:EndEvent",
                    right = "bpmn:BoundaryEvent",
                ),
            ),
            mapped,
        )
    }

    @Test
    fun `CardinalityCheck narrows Long bounds`() {
        val mapped = CheckConfigMapper.map(
            PklCheckPrim.CardinalityCheck("bpmn:Diagram", null, 1L),
        )

        assertEquals(
            MappedCheck.Deterministic(
                CardinalityCheckConfig(element = "bpmn:Diagram", min = null, max = 1),
            ),
            mapped,
        )
    }

    @Test
    fun `PoolLabelCheck resolves enum by name`() {
        val mapped = CheckConfigMapper.map(
            PklCheckPrim.PoolLabelCheck("WHITE_BOX_NAMED_BY_PROCESS"),
        )

        assertEquals(
            MappedCheck.Deterministic(
                PoolLabelCheckConfig(mode = PoolLabelMode.WHITE_BOX_NAMED_BY_PROCESS),
            ),
            mapped,
        )
    }

    @Test
    fun `ElementConstraintCheck preserves the constraint map`() {
        val constraints = mapOf("max" to 10, "allowList" to listOf("A", "B"))
        val mapped = CheckConfigMapper.map(
            PklCheckPrim.ElementConstraintCheck("bpmn:TimerEventDefinition", "TIMER_EXPRESSION", constraints),
        )

        assertEquals(
            MappedCheck.Deterministic(
                ElementConstraintCheckConfig(
                    element = "bpmn:TimerEventDefinition",
                    mode = ElementConstraintMode.TIMER_EXPRESSION,
                    constraints = constraints,
                ),
            ),
            mapped,
        )
    }

    @Test
    fun `CompositeCheck recursively maps sub-checks to deterministic configs`() {
        val sub = PklCheckPrim.SubCheck(
            "labelCase",
            PklCheckPrim.PropertyPatternCheck("name", "^[A-Z].*$", null, null, null),
        )
        val mapped = CheckConfigMapper.map(
            PklCheckPrim.CompositeCheck(listOf("bpmn:Task"), listOf(sub)),
        )

        assertEquals(
            MappedCheck.Composite(
                CompositeCheckConfig(
                    targetTypes = listOf("bpmn:Task"),
                    subChecks = listOf(
                        SubCheckConfig(
                            diagnosticCode = "labelCase",
                            config = PropertyPatternCheckConfig("name", "^[A-Z].*$", null),
                        ),
                    ),
                ),
            ),
            mapped,
        )
    }

    @Test
    fun `CompositeCheck rejects nested CompositeCheck`() {
        val nested = PklCheckPrim.CompositeCheck(emptyList(), emptyList())
        val outer = PklCheckPrim.CompositeCheck(
            emptyList(),
            listOf(PklCheckPrim.SubCheck("nope", nested)),
        )

        assertFailsWith<IllegalArgumentException> {
            CheckConfigMapper.map(outer)
        }
    }

    @Test
    fun `CompositeCheck rejects LlmCheckRule sub-check`() {
        val llmSub = PklCheckPrim.LlmCheckRule("prompt", null)
        val outer = PklCheckPrim.CompositeCheck(
            emptyList(),
            listOf(PklCheckPrim.SubCheck("nope", llmSub)),
        )

        assertFailsWith<IllegalArgumentException> {
            CheckConfigMapper.map(outer)
        }
    }

    @Test
    fun `LlmCheckRule maps prompt and rubric`() {
        val mapped = CheckConfigMapper.map(
            PklCheckPrim.LlmCheckRule("Is this name business-meaningful?", "Fail vague verbs."),
        )

        assertIs<MappedCheck.Llm>(mapped)
        assertEquals("Is this name business-meaningful?", mapped.config.prompt)
        assertEquals("Fail vague verbs.", mapped.config.rubric)
    }
}
