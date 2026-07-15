/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring

import dev.groknull.bpmner.authoring.internal.domain.BpmnFidelityCode
import dev.groknull.bpmner.authoring.internal.domain.BpmnFidelityIssue
import dev.groknull.bpmner.authoring.internal.domain.BpmnFidelityReport
import dev.groknull.bpmner.authoring.internal.domain.BpmnFidelitySeverity
import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.conformance.BpmnDiagnostic
import dev.groknull.bpmner.conformance.BpmnDiagnosticSeverity
import dev.groknull.bpmner.contract.ProcessContract
import org.jmolecules.architecture.onion.simplified.ApplicationRing

/**
 * Published `authoring` port: checks that a generated [BpmnDefinition] preserves the
 * topology declared by its source [ProcessContract].
 *
 * Callers from other modules (e.g. `repair`) inject this interface; the implementing bean
 * ([dev.groknull.bpmner.authoring.internal.domain.BpmnContractFidelityChecker]) lives in
 * `authoring.internal.domain` and is enforced there by Spring Modulith's `verify()`.
 *
 * Rationale (ADR-009 (port-fronting) disposition a; S9): `BpmnContractFidelityChecker` was a root-package
 * `internal class` that leaked across the `authoring → repair` boundary. Relocating the
 * implementation behind this port walls the concrete class inside `*.internal.*` so
 * Modulith's mechanism-1 enforcement fires on any future direct cross-module reach.
 */
@ApplicationRing
interface BpmnContractFidelityPort {
    /**
     * Check that [definition] faithfully encodes the topology declared by [contract].
     * Returns a list of [BpmnDiagnostic] representing any topology discrepancies.
     */
    fun check(contract: ProcessContract, definition: BpmnDefinition): List<BpmnDiagnostic>

    /**
     * Check that [definition] faithfully encodes the topology declared by [contract],
     * returning a structured [BpmnFidelityReport] so callers never need to re-parse
     * diagnostic message strings to recover fidelity codes.
     *
     * The primary implementation overrides this directly to avoid any string round-trip.
     * The default implementation reconstructs the report from [check] by parsing the
     * `[CODE] message` format produced by the primary implementation — this provides
     * correct behaviour for test doubles that only implement [check].
     */
    fun checkDetailed(contract: ProcessContract, definition: BpmnDefinition): BpmnFidelityReport {
        val diagnostics = check(contract, definition)
        val issues = diagnostics.map { diagnostic ->
            val codeStr = diagnostic.message.substringBefore("]").removePrefix("[")
            val code = runCatching { BpmnFidelityCode.valueOf(codeStr) }
                .getOrDefault(BpmnFidelityCode.DECISION_GATEWAY_MISSING)
            val msg = diagnostic.message.substringAfter("] ")
            BpmnFidelityIssue(
                code = code,
                severity = if (diagnostic.severity == BpmnDiagnosticSeverity.ERROR) {
                    BpmnFidelitySeverity.ERROR
                } else {
                    BpmnFidelitySeverity.WARNING
                },
                message = msg,
                bpmnElementId = diagnostic.elementId,
            )
        }
        return BpmnFidelityReport(issues = issues)
    }
}
