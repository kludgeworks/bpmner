/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring

import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.contract.ProcessContract
import org.jmolecules.architecture.hexagonal.PrimaryPort

/**
 * Published `authoring` port: deterministically propagates contract-side
 * [dev.groknull.bpmner.contract.DefaultBranch] semantics to [BpmnDefinition] edges.
 *
 * Callers from other modules (e.g. `repair`) inject this interface; the implementing bean
 * ([dev.groknull.bpmner.authoring.internal.domain.DefaultFlowAssigner]) lives in
 * `authoring.internal.domain` and is enforced there by Spring Modulith's `verify()`.
 *
 * Rationale (ADR-451-8 disposition a; S9): `DefaultFlowAssigner` was a root-package
 * `internal class` that leaked across the `authoring → repair` boundary. Relocating the
 * implementation behind this port walls the concrete class inside `*.internal.*` so
 * Modulith's mechanism-1 enforcement fires on any future direct cross-module reach.
 */
@PrimaryPort
fun interface BpmnDefaultFlowPort {
    /**
     * Stamps `isDefault = true` on the outbound edge from each decision gateway that the
     * [contract] marks as a [dev.groknull.bpmner.contract.DefaultBranch]. Returns a copy of
     * [definition] with the corrected edges; returns [definition] unchanged when no default
     * branches are present.
     */
    fun assign(contract: ProcessContract, definition: BpmnDefinition): BpmnDefinition
}
