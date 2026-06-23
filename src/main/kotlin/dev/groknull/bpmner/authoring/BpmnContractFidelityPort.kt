/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring

import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.contract.ProcessContract
import org.jmolecules.architecture.hexagonal.PrimaryPort

/**
 * Published `authoring` port: checks that a generated [BpmnDefinition] preserves the
 * topology declared by its source [ProcessContract].
 *
 * Callers from other modules (e.g. `repair`) inject this interface; the implementing bean
 * ([dev.groknull.bpmner.authoring.internal.domain.BpmnContractFidelityChecker]) lives in
 * `authoring.internal.domain` and is enforced there by Spring Modulith's `verify()`.
 *
 * Rationale (ADR-451-8 disposition a; S9): `BpmnContractFidelityChecker` was a root-package
 * `internal class` that leaked across the `authoring → repair` boundary. Relocating the
 * implementation behind this port walls the concrete class inside `*.internal.*` so
 * Modulith's mechanism-1 enforcement fires on any future direct cross-module reach.
 */
@PrimaryPort
fun interface BpmnContractFidelityPort {
    /**
     * Check that [definition] faithfully encodes the topology declared by [contract].
     * Returns a [BpmnFidelityReport] whose [BpmnFidelityReport.isValid] is `true` when
     * no ERROR-severity issues are found.
     */
    fun check(contract: ProcessContract, definition: BpmnDefinition): BpmnFidelityReport
}
