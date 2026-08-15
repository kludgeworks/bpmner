/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring

import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.contract.ProcessContract
import org.jmolecules.architecture.onion.simplified.ApplicationRing

/**
 * Published `authoring` port: deterministically stamps every BPMN attribute the source
 * [ProcessContract] fully determines onto a generated [BpmnDefinition] — the contract's value
 * always wins, so the pass corrects rather than rejects.
 *
 * Callers from other modules (e.g. `repair`) inject this interface; the implementing bean
 * ([dev.groknull.bpmner.authoring.internal.domain.ContractConformancePass]) lives in
 * `authoring.internal.domain` and is enforced there by Spring Modulith's `verify()`.
 *
 * Note `dev.groknull.bpmner.conformance` is an unrelated module (`BpmnDiagnostic`,
 * `BpmnRepairScope`); this port lives in `authoring` and does not collide with it, but the
 * names read similarly — see ADR-013.
 */
@ApplicationRing
fun interface BpmnContractConformancePort {
    /**
     * Returns a copy of [definition] with every contract-determined attribute stamped to the
     * value [contract] declares, plus the list of corrections applied. Returns [definition]
     * unchanged (with an empty correction list) when nothing needed correcting.
     */
    fun conform(contract: ProcessContract, definition: BpmnDefinition): BpmnConformance
}
