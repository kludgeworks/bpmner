/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain

import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnElementIndex
import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.bpmn.BpmnStartEvent
import dev.groknull.bpmner.bpmn.LaidOutProcessGraph
import dev.groknull.bpmner.bpmn.RenderedBpmn
import dev.groknull.bpmner.bpmn.RepairKind
import dev.groknull.bpmner.conformance.BpmnDiagnostic
import dev.groknull.bpmner.conformance.BpmnDiagnosticSeverity
import dev.groknull.bpmner.conformance.BpmnDiagnosticSource
import dev.groknull.bpmner.conformance.BpmnEvaluation
import dev.groknull.bpmner.conformance.BpmnFingerprintService
import dev.groknull.bpmner.conformance.GlobalDiagnostics
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.repair.BpmnAttemptHistory
import dev.groknull.bpmner.ruleset.RuleRegistry
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BpmnDeterministicNormalizerTest {
    private val patchApplier = mock(BpmnPatchApplicationPort::class.java)
    private val advancer = mock(BpmnRepairAdvancer::class.java)
    private val firstHandler = handler("first")
    private val secondHandler = handler("second")
    private val normalizer = BpmnDeterministicNormalizer(
        BpmnLocalModelFixHandlerRegistry(listOf(firstHandler, secondHandler)),
        mock(RuleRegistry::class.java),
        patchApplier,
        BpmnFingerprintService(),
        advancer,
    )

    private fun <T> anyNonNull(): T {
        org.mockito.ArgumentMatchers.any<T>()
        @Suppress("UNCHECKED_CAST")
        return null as T
    }

    @Test
    fun `applies multiple local diagnostics in order before one revalidation`() {
        val initial = definition("initial")
        val first = definition("first")
        val second = definition("second")
        val evaluation = evaluation(initial, listOf(diagnostic("first", "one"), diagnostic("second", "two")))
        val stable = evaluation(second, emptyList())
        val reasons = mutableListOf<String?>()
        val results = ArrayDeque<PatchApplicationResult>(
            listOf(
                PatchApplicationResult.Success(first),
                PatchApplicationResult.Success(second),
                PatchApplicationResult.NoOp,
                PatchApplicationResult.NoOp,
            ),
        )
        `when`(patchApplier.apply(anyNonNull(), anyNonNull())).thenAnswer { invocation ->
            reasons += (invocation.arguments[1] as BpmnRepairPatch).reason
            results.removeFirst()
        }
        `when`(advancer.revalidateAndAdvance(anyNonNull(), anyNonNull(), anyNonNull(), anyNonNull(), anyBoolean()))
            .thenReturn(stable)

        normalizer.normalize(evaluation)

        verify(patchApplier, org.mockito.Mockito.times(2)).apply(anyNonNull(), anyNonNull())
        assertEquals(listOf("LOCAL_MODEL_FIX: first on one", "LOCAL_MODEL_FIX: second on two"), reasons.take(2))
        verify(advancer).revalidateAndAdvance(
            anyNonNull(),
            anyNonNull(),
            anyNonNull(),
            anyNonNull(),
            org.mockito.ArgumentMatchers.eq(false),
        )
    }

    @Test
    fun `refreshes diagnostics between changed normalization passes`() {
        val initial = definition("initial")
        val first = definition("first")
        val second = definition("second")
        val evaluation = evaluation(initial, listOf(diagnostic("first", "one")))
        val refreshed = evaluation(first, listOf(diagnostic("second", "two")))
        val stable = evaluation(second, emptyList())
        `when`(patchApplier.apply(anyNonNull(), anyNonNull())).thenReturn(
            PatchApplicationResult.Success(first),
            PatchApplicationResult.Success(second),
        )
        `when`(advancer.revalidateAndAdvance(anyNonNull(), anyNonNull(), anyNonNull(), anyNonNull(), anyBoolean()))
            .thenReturn(refreshed, stable)

        assertEquals(stable, normalizer.normalize(evaluation))

        verify(patchApplier, org.mockito.Mockito.times(2)).apply(anyNonNull(), anyNonNull())
        verify(advancer, org.mockito.Mockito.times(2)).revalidateAndAdvance(
            anyNonNull(),
            anyNonNull(),
            anyNonNull(),
            anyNonNull(),
            org.mockito.ArgumentMatchers.eq(false),
        )
    }

    @Test
    fun `no-op candidates fall through and stable normalization is a no-op`() {
        val initial = definition("initial")
        val changed = definition("changed")
        val evaluation = evaluation(initial, listOf(diagnostic("first", "one"), diagnostic("second", "two")))
        `when`(patchApplier.apply(anyNonNull(), anyNonNull())).thenReturn(
            PatchApplicationResult.NoOp,
            PatchApplicationResult.Success(changed),
            PatchApplicationResult.NoOp,
            PatchApplicationResult.NoOp,
        )
        `when`(advancer.revalidateAndAdvance(anyNonNull(), anyNonNull(), anyNonNull(), anyNonNull(), anyBoolean()))
            .thenReturn(evaluation(changed, emptyList()))

        normalizer.normalize(evaluation)
        verify(patchApplier, org.mockito.Mockito.times(2)).apply(anyNonNull(), anyNonNull())

        val stable = evaluation(initial, emptyList())
        org.mockito.Mockito.clearInvocations(advancer)
        assertEquals(stable, normalizer.normalize(stable))
        verifyNoInteractions(advancer)
    }

    @Test
    fun `rejects a repeated definition fingerprint as a normalization cycle`() {
        val initial = definition("initial")
        val changed = definition("changed")
        val evaluation = evaluation(initial, listOf(diagnostic("first", "one")))
        val refreshed = evaluation(changed, listOf(diagnostic("first", "one")))
        `when`(patchApplier.apply(anyNonNull(), anyNonNull())).thenReturn(
            PatchApplicationResult.Success(changed),
            PatchApplicationResult.Success(initial),
        )
        `when`(advancer.revalidateAndAdvance(anyNonNull(), anyNonNull(), anyNonNull(), anyNonNull(), anyBoolean()))
            .thenReturn(refreshed)

        val error = assertFailsWith<IllegalStateException> { normalizer.normalize(evaluation) }

        assertEquals(true, error.message?.contains("normalization cycle"))
    }

    private fun handler(name: String): BpmnLocalModelFixHandler = object : BpmnLocalModelFixHandler {
        override val handlerName = name

        override fun buildPatch(
            definition: BpmnDefinition,
            elementId: String,
            config: HandlerConfig,
        ): List<BpmnPatchOperation> = listOf(BpmnPatchOperation(BpmnPatchOperationType.SET_NODE_NAME, elementId))
    }

    private fun diagnostic(handler: String, elementId: String) = BpmnDiagnostic(
        source = BpmnDiagnosticSource.LINT,
        message = handler,
        severity = BpmnDiagnosticSeverity.ERROR,
        kind = RepairKind.LOCAL_MODEL_FIX,
        fixHandler = handler,
        elementId = elementId,
    )

    private fun definition(name: String) = BpmnDefinition(
        processId = "Process_1",
        processName = name,
        nodes = listOf(BpmnStartEvent("Start_1", "Start")),
        sequences = emptyList(),
    )

    private fun evaluation(definition: BpmnDefinition, diagnostics: List<BpmnDiagnostic>): BpmnRepairEvaluation {
        val rendered = RenderedBpmn(
            definition,
            "<process/>",
            BpmnElementIndex("Process_1", nodeObjectRefs = emptyMap(), edgeObjectRefs = emptyMap()),
        )
        val evaluated = BpmnEvaluation(definition, rendered, diagnostics, GlobalDiagnostics(diagnostics), "<process/>")
        return BpmnRepairEvaluation(
            request = BpmnRequest("description"),
            graph = mock(LaidOutProcessGraph::class.java),
            rendered = rendered,
            evaluation = evaluated,
            messages = emptyList(),
            history = BpmnAttemptHistory(),
            contract = mock(ProcessContract::class.java),
            repairAttempts = 7,
        )
    }
}
