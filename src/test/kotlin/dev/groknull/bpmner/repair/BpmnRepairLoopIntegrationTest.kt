/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair

import com.embabel.agent.api.common.AgentPlatformTypedOps
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.Budget
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.ReplanRequestedException
import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest
import dev.groknull.bpmner.alignment.BpmnAligner
import dev.groknull.bpmner.alignment.BpmnAlignmentReport
import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.bpmn.GenerationMode
import dev.groknull.bpmner.bpmn.RepairKind
import dev.groknull.bpmner.bpmn.internal.model.BpmnDefinition
import dev.groknull.bpmner.bpmn.internal.model.BpmnElementIndex
import dev.groknull.bpmner.bpmn.internal.model.LaidOutProcessGraph
import dev.groknull.bpmner.bpmn.internal.model.RenderedBpmn
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.ProcessContractExtractor
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.generation.BpmnGenerationStatus
import dev.groknull.bpmner.generation.BpmnProcessGenerator
import dev.groknull.bpmner.generation.BpmnResult
import dev.groknull.bpmner.generation.ValidatedOutline
import dev.groknull.bpmner.layout.BpmnLayoutPort
import dev.groknull.bpmner.readiness.BpmnReadinessInvoker
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessVerdict
import dev.groknull.bpmner.repair.internal.domain.BpmnLlmRepairApplier
import dev.groknull.bpmner.repair.internal.domain.BpmnLocalFixApplier
import dev.groknull.bpmner.repair.internal.domain.BpmnRepairAdvancer
import dev.groknull.bpmner.repair.internal.domain.BpmnRepairEvaluation
import dev.groknull.bpmner.validation.BpmnDiagnostic
import dev.groknull.bpmner.validation.BpmnDiagnosticSeverity
import dev.groknull.bpmner.validation.BpmnDiagnosticSource
import dev.groknull.bpmner.validation.BpmnEvaluation
import dev.groknull.bpmner.validation.BpmnRepairScope
import dev.groknull.bpmner.validation.BpmnXsdValidationPort
import dev.groknull.bpmner.validation.GlobalDiagnostics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean

@SpringBootTest
@ActiveProfiles("offline")
@TestPropertySource(
    properties = [
        "embabel.agent.platform.models.anthropic.api-key=mock-key",
        "embabel.agent.platform.models.gemini.api-key=mock-key",
        "embabel.agent.platform.models.mistralai.api-key=mock-key",
        "embabel.agent.platform.models.openai.api-key=mock-key",
    ],
)
class BpmnRepairLoopIntegrationTest : EmbabelMockitoIntegrationTest() {
    @Autowired
    private lateinit var platform: AgentPlatform

    @MockitoBean
    private lateinit var readinessInvoker: BpmnReadinessInvoker

    @MockitoBean
    private lateinit var contractExtractor: ProcessContractExtractor

    @MockitoBean
    private lateinit var processGenerator: BpmnProcessGenerator

    @MockitoBean
    private lateinit var layoutPort: BpmnLayoutPort

    @MockitoBean
    private lateinit var xsdValidationPort: BpmnXsdValidationPort

    @MockitoBean
    private lateinit var aligner: BpmnAligner

    @MockitoBean
    private lateinit var localFixApplier: BpmnLocalFixApplier

    @MockitoBean
    private lateinit var llmRepairApplier: BpmnLlmRepairApplier

    @MockitoBean
    private lateinit var advancer: BpmnRepairAdvancer

    private fun <T> anyNonNull(): T {
        ArgumentMatchers.any<T>()
        // False positive: Mockito's any() matcher returns null at runtime but the generic
        // return type satisfies the call-site expectation.  The cast is the canonical
        // Kotlin-Mockito bridge pattern and is safe in the Mockito stub context.
        @Suppress("UNCHECKED_CAST")
        return null as T
    }

    private fun createEvaluation(
        request: BpmnRequest,
        diagnostics: List<BpmnDiagnostic>,
        validatedXml: String? = "<process/>",
    ): BpmnRepairEvaluation {
        val definition = mock(BpmnDefinition::class.java)
        val contract = mock(ProcessContract::class.java)
        val graph = mock(LaidOutProcessGraph::class.java)
        val elementIndex = BpmnElementIndex(
            processId = "process-1",
            nodeObjectRefs = emptyMap(),
            edgeObjectRefs = emptyMap(),
        )
        val rendered = RenderedBpmn(
            definition = definition,
            xml = validatedXml ?: "",
            elementIndex = elementIndex,
        )
        val bpmnEvaluation = BpmnEvaluation(
            definition = definition,
            rendered = rendered,
            diagnostics = diagnostics,
            globalDiagnostics = GlobalDiagnostics(diagnostics),
            validatedXml = validatedXml,
        )
        return BpmnRepairEvaluation(
            request = request,
            graph = graph,
            rendered = rendered,
            evaluation = bpmnEvaluation,
            messages = emptyList(),
            history = BpmnAttemptHistory(),
            contract = contract,
            repairAttempts = 0,
        )
    }

    @BeforeEach
    fun setUp() {
        val readyAssessment = ProcessInputAssessment(
            verdict = ReadinessVerdict.READY,
            overallScore = 100,
            dimensions = emptyList(),
            missingAreas = emptyList(),
            clarificationQuestions = emptyList(),
            evidence = emptyList(),
            rationale = "Mocked readiness",
        )
        `when`(readinessInvoker.assess(anyNonNull())).thenReturn(readyAssessment)

        val contract = mock(ValidatedProcessContract::class.java)
        val outline = mock(ValidatedOutline::class.java)
        val graph = mock(LaidOutProcessGraph::class.java)
        val definition = mock(BpmnDefinition::class.java)
        val elementIndex = BpmnElementIndex(
            processId = "process-1",
            nodeObjectRefs = emptyMap(),
            edgeObjectRefs = emptyMap(),
        )
        val rendered = RenderedBpmn(
            definition = definition,
            xml = "<process/>",
            elementIndex = elementIndex,
        )

        `when`(contractExtractor.extract(anyNonNull(), anyNonNull())).thenReturn(contract)
        `when`(processGenerator.createOutline(anyNonNull(), anyNonNull(), anyNonNull())).thenReturn(outline)
        `when`(processGenerator.composeGraph(anyNonNull())).thenReturn(graph)
        `when`(processGenerator.render(anyNonNull(), anyNonNull())).thenReturn(rendered)
        `when`(layoutPort.layout(anyNonNull())).thenReturn("<process/>")
        `when`(xsdValidationPort.validateDetailed(anyNonNull())).thenReturn(emptyList())
        `when`(aligner.align(anyNonNull(), anyNonNull(), anyNonNull(), anyNonNull())).thenReturn(
            mock(BpmnAlignmentReport::class.java),
        )
        org.mockito.Mockito.clearInvocations(localFixApplier, llmRepairApplier, advancer)
    }

    @Test
    fun `broken fixture is repaired to clean within max iterations`() {
        val request = BpmnRequest(processDescription = "Make toast", mode = GenerationMode.SINGLE_SHOT)

        val diagLocal = BpmnDiagnostic(
            source = BpmnDiagnosticSource.LINT,
            message = "Local issue",
            severity = BpmnDiagnosticSeverity.ERROR,
            kind = RepairKind.LOCAL_MODEL_FIX,
            repairScope = BpmnRepairScope.LABEL,
        )

        `when`(advancer.initialEvaluation(anyNonNull(), anyNonNull(), anyNonNull(), anyNonNull()))
            .thenReturn(createEvaluation(request, listOf(diagLocal)))

        `when`(localFixApplier.applyLocalModelFix(anyNonNull()))
            .thenReturn(createEvaluation(request, emptyList()))

        val result = AgentPlatformTypedOps(platform).transform(
            request,
            BpmnResult::class.java,
            ProcessOptions(budget = Budget(actions = 15), ephemeral = false),
        )

        assertEquals(BpmnGenerationStatus.GENERATED, result.status)
        verify(localFixApplier, times(1)).applyLocalModelFix(anyNonNull())
        verify(llmRepairApplier, times(0)).applyLlmLabelPatch(anyNonNull(), anyNonNull(), anyNonNull())
    }

    @Test
    fun `cost aware escalation order is honored`() {
        val request = BpmnRequest(processDescription = "Make toast", mode = GenerationMode.SINGLE_SHOT)

        val diagLocal = BpmnDiagnostic(
            source = BpmnDiagnosticSource.LINT,
            message = "Local issue",
            severity = BpmnDiagnosticSeverity.ERROR,
            kind = RepairKind.LOCAL_MODEL_FIX,
            repairScope = BpmnRepairScope.LABEL,
        )

        val diagLabel = BpmnDiagnostic(
            source = BpmnDiagnosticSource.LINT,
            message = "Label issue",
            severity = BpmnDiagnosticSeverity.ERROR,
            kind = RepairKind.LLM_MODEL_PATCH,
            repairScope = BpmnRepairScope.LABEL,
        )

        val diagStructural = BpmnDiagnostic(
            source = BpmnDiagnosticSource.LINT,
            message = "Structural issue",
            severity = BpmnDiagnosticSeverity.ERROR,
            kind = RepairKind.LLM_MODEL_PATCH,
            repairScope = BpmnRepairScope.OUTLINE,
        )

        val diagRewrite = BpmnDiagnostic(
            source = BpmnDiagnosticSource.LINT,
            message = "Rewrite issue",
            severity = BpmnDiagnosticSeverity.ERROR,
            kind = RepairKind.LLM_XML_REWRITE,
            repairScope = BpmnRepairScope.FULL_PROCESS,
        )

        `when`(advancer.initialEvaluation(anyNonNull(), anyNonNull(), anyNonNull(), anyNonNull()))
            .thenReturn(createEvaluation(request, listOf(diagLocal)))

        `when`(localFixApplier.applyLocalModelFix(anyNonNull()))
            .thenReturn(createEvaluation(request, listOf(diagLabel)))

        `when`(llmRepairApplier.applyLlmLabelPatch(anyNonNull(), anyNonNull(), anyNonNull()))
            .thenReturn(createEvaluation(request, listOf(diagStructural)))

        `when`(llmRepairApplier.applyLlmStructuralPatch(anyNonNull(), anyNonNull(), anyNonNull()))
            .thenReturn(createEvaluation(request, listOf(diagRewrite)))

        `when`(llmRepairApplier.applyFullLlmRewrite(anyNonNull(), anyNonNull(), anyNonNull()))
            .thenReturn(createEvaluation(request, emptyList()))

        val result = AgentPlatformTypedOps(platform).transform(
            request,
            BpmnResult::class.java,
            ProcessOptions(budget = Budget(actions = 15), ephemeral = false),
        )

        assertEquals(BpmnGenerationStatus.GENERATED, result.status)
        verify(localFixApplier, times(1)).applyLocalModelFix(anyNonNull())
        verify(llmRepairApplier, times(1)).applyLlmLabelPatch(anyNonNull(), anyNonNull(), anyNonNull())
        verify(llmRepairApplier, times(1)).applyLlmStructuralPatch(anyNonNull(), anyNonNull(), anyNonNull())
        verify(llmRepairApplier, times(1)).applyFullLlmRewrite(anyNonNull(), anyNonNull(), anyNonNull())
    }

    @Test
    fun `no progress termination is preserved`() {
        val request = BpmnRequest(processDescription = "Make toast", mode = GenerationMode.SINGLE_SHOT)

        val diagLocal = BpmnDiagnostic(
            source = BpmnDiagnosticSource.LINT,
            message = "Local issue",
            severity = BpmnDiagnosticSeverity.ERROR,
            kind = RepairKind.LOCAL_MODEL_FIX,
            repairScope = BpmnRepairScope.LABEL,
        )

        val initialEval = createEvaluation(request, listOf(diagLocal))

        `when`(advancer.initialEvaluation(anyNonNull(), anyNonNull(), anyNonNull(), anyNonNull()))
            .thenReturn(initialEval)

        `when`(localFixApplier.applyLocalModelFix(anyNonNull()))
            .thenThrow(ReplanRequestedException("No progress"))

        val result = AgentPlatformTypedOps(platform).transform(
            request,
            BpmnResult::class.java,
            ProcessOptions(budget = Budget(actions = 15), ephemeral = false),
        )

        // Config maxRepairIterations is 5.
        // It runs 5 iterations of the loop plus a final output-binding invocation (6 times total),
        // then exits the loop returning the initial dirty evaluation.
        assertEquals(BpmnGenerationStatus.GENERATED, result.status)
        verify(localFixApplier, times(6)).applyLocalModelFix(anyNonNull())
        assertTrue(result.xml != null)
    }
}
