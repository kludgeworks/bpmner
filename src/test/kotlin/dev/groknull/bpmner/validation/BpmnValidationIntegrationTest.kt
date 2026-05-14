package dev.groknull.bpmner.validation
import dev.groknull.bpmner.core.BpmnRequest


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
