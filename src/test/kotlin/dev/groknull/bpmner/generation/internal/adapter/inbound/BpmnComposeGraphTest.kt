package dev.groknull.bpmner.generation.internal.adapter.inbound

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.groknull.bpmner.contract.ProcessContractMarkdownRenderer
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.generation.BpmnContractFidelityChecker
import dev.groknull.bpmner.generation.DefaultFlowAssigner
import dev.groknull.bpmner.generation.FlatBpmnDefinition
import dev.groknull.bpmner.generation.ProcessOutline
import dev.groknull.bpmner.generation.ValidatedOutline
import dev.groknull.bpmner.generation.internal.domain.BpmnGraphRenderer
import dev.groknull.bpmner.generation.toSealed
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.context.ApplicationEventPublisher

/**
 * Direct unit test on the highest-risk deterministic stage that was inlined and hand-edited during the
 * #402 rework: [LlmBpmnProcessGenerator.composeGraph]. It is a pure `ValidatedOutline → LaidOutProcessGraph`
 * function (no LLM, no I/O), so we drive it with a fixed outline and pin the ownership maps exactly —
 * `objectOwnersByObjectRef` and `elementOwnersByElementId` (including the `_di` diagram-element keys) must
 * cover every node, edge and the process with the single phase owner, and nothing else.
 */
class BpmnComposeGraphTest {
    // composeGraph touches only `config` (to gate the debug artifact dump — RETURNS_DEEP_STUBS yields
    // dumpArtifacts=false) and `logger`; the remaining collaborators are unused, so plain mocks suffice.
    private val generator = LlmBpmnProcessGenerator(
        config = Mockito.mock(BpmnConfig::class.java, Mockito.RETURNS_DEEP_STUBS),
        metricsCalculator = Mockito.mock(BpmnGeneratorMetrics::class.java),
        fidelityChecker = Mockito.mock(BpmnContractFidelityChecker::class.java),
        defaultFlowAssigner = Mockito.mock(DefaultFlowAssigner::class.java),
        contractRenderer = Mockito.mock(ProcessContractMarkdownRenderer::class.java),
        graphRenderer = Mockito.mock(BpmnGraphRenderer::class.java),
        eventPublisher = Mockito.mock(ApplicationEventPublisher::class.java),
    )

    // MAIN_PHASE_OWNER is private to the generator; pin its value here so a change is a visible diff.
    private val phaseOwner = "generateBpmn"

    private val nodeIds = listOf(
        "StartEvent_1",
        "act-run-credit-check",
        "dec-score-check",
        "act-auto-approve",
        "act-underwriter-review",
        "end-approved",
        "end-reviewed",
    )
    private val edgeIds = listOf("Flow_1", "Flow_2", "Flow_3", "Flow_4", "Flow_5", "Flow_6")

    private fun outlineFromFixture(): ValidatedOutline {
        val json = BpmnComposeGraphTest::class.java.getResource("/parity/canonicalOutlineFlat.json")?.readText()
            ?: error("Fixture not found: /parity/canonicalOutlineFlat.json")
        val flat: FlatBpmnDefinition = jacksonObjectMapper().readValue(json)
        val definition = flat.toSealed()
        val outline = ProcessOutline(
            request = BpmnRequest(processDescription = "credit application"),
            definition = definition,
            metrics = BpmnGeneratorMetrics().calculate(definition),
        )
        return ValidatedOutline(outline = outline)
    }

    @Test
    fun composeGraphStampsEveryElementWithThePhaseOwner() {
        val owned = generator.composeGraph(outlineFromFixture()).ownedGraph

        // objectOwnersByObjectRef: exactly the process + one entry per node and per edge.
        assertEquals(phaseOwner, owned.objectOwnersByObjectRef["process"])
        nodeIds.forEach { assertEquals(phaseOwner, owned.objectOwnersByObjectRef["nodes[id=$it]"], "object owner $it") }
        edgeIds.forEach { assertEquals(phaseOwner, owned.objectOwnersByObjectRef["sequences[id=$it]"], "object owner $it") }
        assertEquals(1 + nodeIds.size + edgeIds.size, owned.objectOwnersByObjectRef.size, "objectOwners coverage")

        // elementOwnersByElementId: the process id + a shape and a `_di` key for every node and edge.
        assertEquals(phaseOwner, owned.elementOwnersByElementId["Process_credit_application"])
        (nodeIds + edgeIds).forEach { id ->
            assertEquals(phaseOwner, owned.elementOwnersByElementId[id], "element owner $id")
            assertEquals(phaseOwner, owned.elementOwnersByElementId["${id}_di"], "di owner $id")
        }
        assertEquals(1 + 2 * (nodeIds.size + edgeIds.size), owned.elementOwnersByElementId.size, "elementOwners coverage")

        // Nothing is owned by anyone else.
        assertTrue(owned.objectOwnersByObjectRef.values.all { it == phaseOwner })
        assertTrue(owned.elementOwnersByElementId.values.all { it == phaseOwner })
    }
}
