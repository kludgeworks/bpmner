package dev.groknull.bpmner.validation.internal.domain

import dev.groknull.bpmner.core.BpmnAutoFixResult
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnDiagnosticSource
import dev.groknull.bpmner.core.BpmnEditSurface
import dev.groknull.bpmner.core.BpmnElementIndex
import dev.groknull.bpmner.core.BpmnLintPhase
import dev.groknull.bpmner.core.BpmnLintRuleCapability
import dev.groknull.bpmner.core.BpmnRepairRoute
import dev.groknull.bpmner.core.BpmnRepairSafety
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.ComposedProcessGraph
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.LintIssue
import dev.groknull.bpmner.core.OutlineMetrics
import dev.groknull.bpmner.core.OwnedElementGraph
import dev.groknull.bpmner.core.ProcessOutline
import dev.groknull.bpmner.core.RenderedBpmn
import dev.groknull.bpmner.core.ValidatedOutline
import dev.groknull.bpmner.core.XsdValidationIssue
import dev.groknull.bpmner.validation.BpmnLintingPort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BpmnDiagnosticNormalizerTest {
    private val localXmlCapability =
        BpmnLintRuleCapability(
            id = "gtw-converging-gateway-unnamed",
            repairRoute = BpmnRepairRoute.LOCAL_XML,
            editSurface = BpmnEditSurface.BPMN_XML,
            repairSafety = BpmnRepairSafety.SAFE_AUTOMATIC,
            fixHandler = "clearGatewayName",
            handlerExists = true,
            replacementMap = null,
        )
    private val llmCapability =
        BpmnLintRuleCapability(
            id = "act-verb-object-name",
            repairRoute = BpmnRepairRoute.LLM,
            editSurface = BpmnEditSurface.NONE,
            repairSafety = BpmnRepairSafety.LLM_ONLY,
            fixHandler = null,
            handlerExists = false,
            replacementMap = null,
        )

    private val stubPort =
        object : BpmnLintingPort {
            override fun lint(
                bpmnXml: String,
                phase: BpmnLintPhase,
            ): List<LintIssue>? = emptyList()

            override fun autoFix(
                bpmnXml: String,
                issues: List<LintIssue>,
                phase: BpmnLintPhase,
            ): BpmnAutoFixResult? = null

            override fun ruleDocs(ruleNames: Collection<String>): Map<String, String> = emptyMap()

            override fun lintRuleCapabilities(): Map<String, BpmnLintRuleCapability> =
                mapOf(
                    localXmlCapability.id to localXmlCapability,
                    llmCapability.id to llmCapability,
                )
        }

    private val normalizer = BpmnDiagnosticNormalizer(stubPort)

    private val emptyIndex =
        BpmnElementIndex(
            processId = "Process_1",
            nodeObjectRefs = emptyMap(),
            edgeObjectRefs = emptyMap(),
            shapeIdsByNodeId = emptyMap(),
            edgeDiagramIdsByEdgeId = emptyMap(),
        )

    private val emptyDefinition =
        BpmnDefinition(
            processId = "Process_1",
            processName = "Test",
            nodes = emptyList(),
            sequences = emptyList(),
        )

    private val emptyGraph =
        LaidOutProcessGraph(
            ownedGraph =
                OwnedElementGraph(
                    composedGraph =
                        ComposedProcessGraph(
                            outline =
                                ValidatedOutline(
                                    ProcessOutline(BpmnRequest("Test"), emptyDefinition, OutlineMetrics(1, 0, 0, 0)),
                                ),
                            definition = emptyDefinition,
                            objectOwnersByObjectRef = emptyMap(),
                        ),
                    elementOwnersByElementId = emptyMap(),
                    objectOwnersByObjectRef = emptyMap(),
                ),
            definition = emptyDefinition,
        )

    @Test
    fun `normalizeLintDiagnostics stamps LOCAL_XML route for known rule`() {
        val issues = listOf(LintIssue(id = null, rule = "klm/gtw-converging-gateway-unnamed", message = "named converging"))

        val diagnostics = normalizer.normalizeLintDiagnostics(issues, emptyIndex, emptyGraph)

        assertEquals(1, diagnostics.size)
        assertEquals(BpmnRepairRoute.LOCAL_XML, diagnostics[0].repairRoute)
        assertEquals(BpmnEditSurface.BPMN_XML, diagnostics[0].editSurface)
        assertEquals("clearGatewayName", diagnostics[0].fixHandler)
    }

    @Test
    fun `normalizeLintDiagnostics stamps LLM route for known LLM rule`() {
        val issues = listOf(LintIssue(id = null, rule = "klm/act-verb-object-name", message = "bad label"))

        val diagnostics = normalizer.normalizeLintDiagnostics(issues, emptyIndex, emptyGraph)

        assertEquals(BpmnRepairRoute.LLM, diagnostics[0].repairRoute)
        assertNull(diagnostics[0].fixHandler)
    }

    @Test
    fun `normalizeLintDiagnostics falls back to LLM for unknown rule`() {
        val issues = listOf(LintIssue(id = null, rule = "klm/some-unknown-rule", message = "unknown"))

        val diagnostics = normalizer.normalizeLintDiagnostics(issues, emptyIndex, emptyGraph)

        assertEquals(BpmnRepairRoute.LLM, diagnostics[0].repairRoute)
        assertNull(diagnostics[0].fixHandler)
        assertNull(diagnostics[0].editSurface)
        assertNull(diagnostics[0].repairSafety)
    }

    @Test
    fun `normalizeXsdDiagnostics does not stamp repairRoute`() {
        val issues = listOf(XsdValidationIssue(message = "xsd error", elementId = null))

        val diagnostics = normalizer.normalizeXsdDiagnostics(issues, mockRendered(), emptyGraph)

        assertEquals(BpmnDiagnosticSource.XSD, diagnostics[0].source)
        assertNull(diagnostics[0].repairRoute)
    }

    @Test
    fun `graphDiagnostic does not stamp repairRoute`() {
        val diagnostic = normalizer.graphDiagnostic(emptyGraph, "graph error")

        assertEquals(BpmnDiagnosticSource.GRAPH, diagnostic.source)
        assertNull(diagnostic.repairRoute)
    }

    @Test
    fun `bpmnlint-plugin-klm prefix is stripped for lookup`() {
        val issues = listOf(LintIssue(id = null, rule = "bpmnlint-plugin-klm/gtw-converging-gateway-unnamed", message = "named"))

        val diagnostics = normalizer.normalizeLintDiagnostics(issues, emptyIndex, emptyGraph)

        assertEquals(BpmnRepairRoute.LOCAL_XML, diagnostics[0].repairRoute)
    }

    private fun mockRendered(): RenderedBpmn =
        RenderedBpmn(
            definition = emptyDefinition,
            xml = "<empty/>",
            elementIndex = emptyIndex,
        )
}
