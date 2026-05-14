package dev.groknull.bpmner.validation

import dev.groknull.bpmner.TestBpmnFixtures.testBpmnDefinition
import dev.groknull.bpmner.TestBpmnFixtures.testLaidOutGraph
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnElementIndex
import dev.groknull.bpmner.core.BpmnFingerprintService
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.RenderedBpmn
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
        val request = BpmnRequest("Make toast")
        val definition = testBpmnDefinition()

        val graph = testLaidOutGraph(definition, request)

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
