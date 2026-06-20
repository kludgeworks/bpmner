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
@ApplicationModule(
    allowedDependencies = ["alignment", "api", "domain", "generation", "readiness", "validation"],
)
internal object ObservabilityModule
