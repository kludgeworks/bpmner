/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.observability

import org.springframework.modulith.ApplicationModule

/**
 * Observability module — purely-outbound event listeners over the rest of the
 * pipeline. No other module imports observability; it consumes
 * generation/validation/alignment/readiness events for metrics and
 * tracing.
 */
// api.GenerationMode is referenced via BpmnRequest.mode at bytecode level — no direct
// import; verify() confirms this grant is load-bearing (removal fails ApplicationModules.verify()).
@ApplicationModule(
    allowedDependencies = ["alignment", "bpmn", "generation", "readiness", "validation"],
)
internal object ObservabilityModule
