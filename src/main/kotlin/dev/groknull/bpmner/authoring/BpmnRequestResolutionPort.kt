/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring

import dev.groknull.bpmner.bpmn.BpmnRequest
import org.jmolecules.architecture.hexagonal.PrimaryPort

/**
 * Published `authoring` port: resolves a shell [BpmnRequestDraft] (containing inline prose or
 * file paths) into a fully resolved [BpmnRequest] suitable for the generation pipeline.
 *
 * Callers from other modules (e.g. `pipeline`) inject this interface; the implementing bean
 * ([BpmnRequestResolver]) lives in the `authoring` root package but is declared `internal`
 * (Kotlin visibility) because it constructor-injects
 * [dev.groknull.bpmner.authoring.internal.adapter.inbound.InputPathResolver], which is an
 * authoring-private type. The port is the cross-module seam.
 *
 * Rationale (ADR-451-8 disposition a; S9): `BpmnRequestResolver` was a root-package
 * `internal class` whose constructor injects `InputPathResolver` (an `authoring.internal.*`
 * type), making it impossible to convert to a non-`internal` public class. Adding this port
 * lets `pipeline` inject the seam while the implementation remains encapsulated.
 */
@PrimaryPort
fun interface BpmnRequestResolutionPort {
    /**
     * Resolves a shell [BpmnRequestDraft] — which may reference inline prose, file paths,
     * and optional style-guide content — into a validated [BpmnRequest].
     */
    fun resolveShellRequest(draft: BpmnRequestDraft): BpmnRequest
}
