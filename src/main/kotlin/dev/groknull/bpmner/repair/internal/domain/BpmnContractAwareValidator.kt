/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain

import dev.groknull.bpmner.authoring.BpmnContractFidelityPort
import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.LaidOutProcessGraph
import dev.groknull.bpmner.bpmn.RenderedBpmn
import dev.groknull.bpmner.conformance.BpmnDiagnostic
import dev.groknull.bpmner.conformance.BpmnEvaluation
import dev.groknull.bpmner.conformance.BpmnValidator
import dev.groknull.bpmner.conformance.GlobalDiagnostics
import dev.groknull.bpmner.contract.ProcessContract
import org.jmolecules.ddd.annotation.Service
import org.springframework.stereotype.Component

/**
 * Contract-aware validator wrapper that composes the structural [BpmnValidator] (XSD, lint,
 * graph) with the [BpmnContractFidelityPort] (contract→BPMN topology correspondence).
 *
 * Exists in the repair domain layer so that the `validation` module remains contract-agnostic.
 * The [BpmnValidator] interface and [dev.groknull.bpmner.conformance.BpmnEvaluationPipeline]
 * are not modified.
 *
 * Fidelity issues are projected as [BpmnDiagnostic] entries with `source = GRAPH`,
 * preserving their severity so they reach the repair LLM as first-class feedback —
 * identical treatment to lint and XSD issues. The repair prompt already groups by
 * severity and tells the LLM to fix ERRORs and treat WARNINGs as advisory.
 *
 * Fidelity checks are only run when the base evaluation has no blocking diagnostics —
 * there is no value checking contract fidelity when the BPMN is structurally invalid.
 */
@Service
@Component
internal class BpmnContractAwareValidator(
    private val pipeline: BpmnValidator,
    private val fidelityChecker: BpmnContractFidelityPort,
) {
    @Suppress("LongParameterList")
    fun evaluate(
        graph: LaidOutProcessGraph,
        definition: BpmnDefinition,
        rendered: RenderedBpmn?,
        contract: ProcessContract,
        renderFailureMessage: String? = null,
        repairAttempts: Int,
    ): BpmnEvaluation {
        val base = pipeline.evaluate(graph, definition, rendered, renderFailureMessage, repairAttempts)
        // If structural validation already failed, fidelity is not meaningful yet.
        if (base.blockingDiagnostics.isNotEmpty()) return base
        val fidelityDiagnostics = fidelityChecker.check(contract, definition)
        if (fidelityDiagnostics.isEmpty()) return base
        val allDiagnostics = base.diagnostics + fidelityDiagnostics
        return base.copy(
            diagnostics = allDiagnostics,
            globalDiagnostics = GlobalDiagnostics(allDiagnostics),
            validatedXml = null,
        )
    }

    fun logDiagnosticSummary(diagnostics: List<BpmnDiagnostic>) = pipeline.logDiagnosticSummary(diagnostics)
}
