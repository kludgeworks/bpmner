/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.telemetry

import org.springframework.modulith.ApplicationModule

/**
 * Telemetry module — purely-outbound event listeners over the rest of the
 * pipeline. No other module imports telemetry; it consumes
 * authoring/conformance/alignment/layout/readiness events for metrics and
 * tracing.
 */
// bpmn.GenerationMode is referenced via BpmnRequest.mode at bytecode level — no direct
// import; verify() confirms this grant is load-bearing (removal fails ApplicationModules.verify()).
// layout is listed because BpmnPipelineObserver observes BpmnLayoutCompletedEvent (ss-2) to
// emit the LAYOUT_COMPLETE snapshot; BpmnLayoutCompletedEvent is the layout module's published API.
@ApplicationModule(
    allowedDependencies = ["alignment", "authoring", "bpmn", "conformance", "layout", "readiness"],
)
internal object TelemetryModule
