/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.observability

import org.springframework.modulith.ApplicationModule

/**
 * Observability module — purely-outbound event listeners over the rest of the
 * pipeline. No other module imports observability; it consumes
 * generation/validation/repair/alignment/readiness events for metrics and
 * tracing.
 */
// observability transitively depends on api via api.GenerationMode (exposed through
// generation.BpmnResult / similar types passed around in observability event handlers).
@ApplicationModule(
    allowedDependencies = ["alignment", "api", "domain", "generation", "readiness", "repair", "validation"],
)
internal object ObservabilityModule
