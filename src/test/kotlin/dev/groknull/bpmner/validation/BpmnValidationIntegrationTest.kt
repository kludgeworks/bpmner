/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.validation

import dev.groknull.bpmner.TestBpmnFixtures.testBpmnDefinition
import dev.groknull.bpmner.TestBpmnFixtures.testLaidOutGraph
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnElementIndex
import dev.groknull.bpmner.core.RenderedBpmn
import dev.groknull.bpmner.validation.BpmnDefinitionValidator
import dev.groknull.bpmner.validation.BpmnDiagnosticNormalizer
import dev.groknull.bpmner.validation.BpmnEvaluationPipeline
import dev.groknull.bpmner.validation.BpmnFingerprintService
import dev.groknull.bpmner.validation.BpmnLintJsEngine
import dev.groknull.bpmner.validation.BpmnLintService
import dev.groknull.bpmner.validation.BpmnXsdValidator
import dev.groknull.bpmner.validation.PklRuleCapabilityAdapter
import dev.groknull.bpmner.validation.RuleCatalogService
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BpmnValidationIntegrationTest {
    private val lintService =
        BpmnLintService(
            catalogService = RuleCatalogService(),
            engine =
            BpmnLintJsEngine().apply {
                init()
            },
            pklAdapter = PklRuleCapabilityAdapter(RuleCatalogService()),
        )
    private val xsdValidator = BpmnXsdValidator()
    private val validator =
        BpmnEvaluationPipeline(
            config = BpmnConfig(),
            bpmnLintingPort = lintService,
            bpmnXsdValidationPort = xsdValidator,
            bpmnDefinitionValidator = BpmnDefinitionValidator(),
            normalizer = BpmnDiagnosticNormalizer(lintService),
            fingerprints = BpmnFingerprintService(),
        )

    @Test
    fun `full validation cycle of toast sample`() {
        lintService.init()
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
