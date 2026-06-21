/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.telemetry.internal.adapter.inbound

import com.embabel.agent.api.event.AbstractAgentProcessEvent
import com.embabel.agent.core.AgentProcess

/**
 * Carries the framework-rendered run-cost summary ([AgentProcess.costInfoString]) to SSE clients.
 *
 * Extending [AbstractAgentProcessEvent] means it serialises with `type = "BpmnRunCostEvent"` (via the
 * `@JsonTypeInfo(use = SIMPLE_NAME)` on the sealed `AgenticEvent`) and is routed to the per-process
 * SSE stream by embabel-webmvc — the same path bpmner's `BpmnSnapshotEvent` already uses.
 */
class BpmnRunCostEvent(
    process: AgentProcess,
    val costSummary: String,
) : AbstractAgentProcessEvent(process)
