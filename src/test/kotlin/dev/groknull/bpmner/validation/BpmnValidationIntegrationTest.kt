/*
 * Copyright (c) 2026 The Project Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package dev.groknull.bpmner.validation

import dev.groknull.bpmner.TestBpmnFixtures.testBpmnDefinition
import dev.groknull.bpmner.TestBpmnFixtures.testLaidOutGraph
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnElementIndex
import dev.groknull.bpmner.core.RenderedBpmn
import dev.groknull.bpmner.validation.BpmnFingerprintService
import dev.groknull.bpmner.validation.internal.adapter.outbound.BpmnLintJsEngine
import dev.groknull.bpmner.validation.internal.adapter.outbound.BpmnLintService
import dev.groknull.bpmner.validation.internal.adapter.outbound.BpmnXsdValidator
import dev.groknull.bpmner.validation.internal.adapter.outbound.PklRuleCapabilityAdapter
import dev.groknull.bpmner.validation.internal.adapter.outbound.RuleCatalogService
import dev.groknull.bpmner.validation.internal.domain.BpmnDefinitionValidator
import dev.groknull.bpmner.validation.internal.domain.BpmnDiagnosticNormalizer
import dev.groknull.bpmner.validation.internal.domain.BpmnEvaluationPipeline
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
                        shapeIdsByNodeId = emptyMap(),
                        edgeDiagramIdsByEdgeId = emptyMap(),
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
            result.diagnostics.none { it.category == "error" },
            "Sample toast process should be valid. Diagnostics: ${result.diagnostics}",
        )
    }
}
