/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.validation

import dev.groknull.bpmner.TestBpmnFixtures.testBpmnDefinition
import dev.groknull.bpmner.TestBpmnFixtures.testLaidOutGraph
import dev.groknull.bpmner.api.BpmnRule
import dev.groknull.bpmner.api.RuleEvaluation
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnElementIndex
import dev.groknull.bpmner.core.RenderedBpmn
import dev.groknull.bpmner.rules.RuleEngine
import dev.groknull.bpmner.rules.RuleRegistry
import dev.groknull.bpmner.validation.internal.adapter.outbound.RuleEngineLintingAdapter
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BpmnValidationIntegrationTest {
    private val emptyRegistry =
        object : RuleRegistry {
            override fun activeRules(): List<BpmnRule> = emptyList()
            override fun ruleById(id: String): BpmnRule? = null
        }
    private val noopEngine = RuleEngine { RuleEvaluation(diagnostics = emptyList()) }
    private val lintingPort = RuleEngineLintingAdapter(noopEngine, emptyRegistry)
    private val xsdValidator = BpmnXsdValidator()
    private val validator =
        BpmnEvaluationPipeline(
            config = BpmnConfig(),
            bpmnLintingPort = lintingPort,
            bpmnXsdValidationPort = xsdValidator,
            bpmnDefinitionValidator = BpmnDefinitionValidator(),
            normalizer = BpmnDiagnosticNormalizer(lintingPort),
            fingerprints = BpmnFingerprintService(),
        )

    @Test
    fun `full validation cycle of toast sample`() {
        val definition = testBpmnDefinition()

        val graph = testLaidOutGraph(definition)

        val rendered =
            RenderedBpmn(
                definition = definition,
                xml = "MOCK_XML",
                elementIndex =
                BpmnElementIndex(
                    processId = definition.processId,
                    nodeObjectRefs = emptyMap(),
                    edgeObjectRefs = emptyMap(),
                ),
                sourceGraph = graph,
            )

        val result =
            validator.evaluate(
                graph = graph,
                definition = definition,
                rendered = rendered,
                repairAttempts = 0,
            )

        assertNotNull(result)
        assertTrue(
            result.diagnostics.none { it.isBlocking },
            "Sample toast process should be valid. Diagnostics: ${result.diagnostics}",
        )
    }
}
