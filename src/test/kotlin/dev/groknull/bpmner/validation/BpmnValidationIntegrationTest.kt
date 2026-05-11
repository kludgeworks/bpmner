package dev.groknull.bpmner.validation

import dev.groknull.bpmner.core.*
import dev.groknull.bpmner.validation.internal.adapter.outbound.BpmnLintService
import dev.groknull.bpmner.validation.internal.adapter.outbound.BpmnXsdValidator
import dev.groknull.bpmner.validation.internal.adapter.outbound.RuleCatalogService
import dev.groknull.bpmner.validation.internal.domain.BpmnDefinitionValidator
import dev.groknull.bpmner.validation.internal.domain.BpmnDiagnosticNormalizer
import dev.groknull.bpmner.validation.internal.domain.BpmnEvaluationPipeline
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BpmnValidationIntegrationTest {

    private val lintService = BpmnLintService(catalogService = RuleCatalogService())
    private val xsdValidator = BpmnXsdValidator()
    private val validator = BpmnEvaluationPipeline(
        config = BpmnConfig(),
        bpmnLintingPort = lintService,
        bpmnXsdValidationPort = xsdValidator,
        bpmnDefinitionValidator = BpmnDefinitionValidator(),
        normalizer = BpmnDiagnosticNormalizer(),
        fingerprints = BpmnFingerprintService(),
    )

    @Test
    fun `full validation cycle of toast sample`() {
        lintService.init()
        val request = BpmnRequest("Make toast")
        val definition = toastDefinition()

        // Mocking graph and rendered for a simple integration test
        val graph = LaidOutProcessGraph(
            OwnedElementGraph(
                ComposedProcessGraph(
                    ValidatedOutline(ProcessOutline(request, definition, OutlineMetrics(1, 0, 0, 0))),
                    definition,
                    emptyMap()
                ),
                emptyMap(),
                emptyMap()
            ),
            definition
        )

        val rendered = RenderedBpmn(
            definition = definition,
            xml = "MOCK_XML",
            elementIndex = BpmnElementIndex(
                processId = definition.processId,
                nodeObjectRefs = emptyMap(),
                edgeObjectRefs = emptyMap(),
                shapeIdsByNodeId = emptyMap(),
                edgeDiagramIdsByEdgeId = emptyMap(),
            ),
            sourceGraph = graph,
        )

        val result = validator.evaluate(
            graph = graph,
            definition = definition,
            rendered = rendered,
            repairAttempts = 0
        )

        assertNotNull(result)
        assertTrue(result.diagnostics.none { it.category == "error" }, "Sample toast process should be valid. Diagnostics: ${result.diagnostics}")
    }

    private fun toastDefinition() = BpmnDefinition(
        processId = "Process_MakeToast",
        processName = "Make toast",
        nodes = listOf(
            BpmnNode("StartEvent_1", "Order received", NodeType.START_EVENT, BpmnBounds(80.0, 120.0, 36.0, 36.0)),
            BpmnNode("Task_1", "Toast bread", NodeType.SERVICE_TASK, BpmnBounds(180.0, 98.0, 100.0, 80.0)),
            BpmnNode("EndEvent_1", "Toast served", NodeType.END_EVENT, BpmnBounds(320.0, 120.0, 36.0, 36.0)),
        ),
        sequences = listOf(
            BpmnEdge(
                "Flow_1",
                "StartEvent_1",
                "Task_1",
                waypoints = listOf(BpmnWaypoint(116.0, 138.0), BpmnWaypoint(180.0, 138.0)),
            ),
            BpmnEdge(
                "Flow_2",
                "Task_1",
                "EndEvent_1",
                waypoints = listOf(BpmnWaypoint(280.0, 138.0), BpmnWaypoint(320.0, 138.0)),
            ),
        ),
    )
}
