/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.telemetry

import org.springframework.modulith.ApplicationModule

/**
 * Telemetry module — purely-outbound event listeners over the rest of the
 * pipeline. No other module imports telemetry; it consumes framework
 * lifecycle events for a per-run cost/timing summary and debug-level
 * lifecycle logging. The author-facing progress stream is owned by `pipeline`'s `RunUpdate`
 * anti-corruption layer, not by this module.
 */
@ApplicationModule
internal object TelemetryModule
