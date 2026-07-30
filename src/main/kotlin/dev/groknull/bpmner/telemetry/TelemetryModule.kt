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
 * lifecycle logging. The author-facing progress stream (epic #605) is owned
 * by `pipeline`'s `RunUpdate` anti-corruption layer, not by this module.
 */
// bpmn.GenerationMode is referenced via BpmnRequest.mode at bytecode level — no direct
// import; verify() confirms this grant is load-bearing (removal fails ApplicationModules.verify()).
@ApplicationModule
internal object TelemetryModule
