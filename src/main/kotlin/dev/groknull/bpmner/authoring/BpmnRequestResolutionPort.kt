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
 * (`authoring.internal.domain.BpmnRequestResolver`) lives in `authoring.internal.domain` so
 * that Modulith's `verify()` (mechanism 1) enforces the `*.internal.*` package path and
 * rejects any future cross-module direct reach. The port is the cross-module seam.
 *
 * Rationale (ADR-451-8 disposition a; S9): `BpmnRequestResolver` was a root-package
 * `internal class` imported cross-module by `pipeline`. S9 relocates the implementation to
 * `authoring.internal.domain` (completing disposition-a) and exposes this port as the
 * published cross-module seam, closing the mechanism-1 gap identified in REVIEW-451-9 #5.
 */
@PrimaryPort
fun interface BpmnRequestResolutionPort {
    /**
     * Resolves a shell [BpmnRequestDraft] — which may reference inline prose, file paths,
     * and optional style-guide content — into a validated [BpmnRequest].
     */
    fun resolveShellRequest(draft: BpmnRequestDraft): BpmnRequest
}
