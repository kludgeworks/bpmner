/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.readiness

import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import org.jmolecules.event.annotation.DomainEvent

/**
 * Published by the orchestrator ([dev.groknull.bpmner.pipeline.internal.adapter.inbound.BpmnGenerationAgent],
 * not [BpmnReadinessAgent]) once a readiness assessment completes.
 *
 * [processId] identifies the **outer, web-facing** generation run — readiness runs as its own
 * scoped, ephemeral Embabel sub-process (see `AgentPlatformBpmnReadinessInvoker`), so
 * `AgentProcess.get()` resolves to the wrong (child) process anywhere inside that sub-process.
 * The orchestrator, which starts and awaits the sub-process synchronously, is the only call
 * site with the correct id in scope — the standard "caller attaches the correlation id" pattern
 * for events crossing an isolated execution boundary. Nullable only defensively; a listener
 * should treat a `null` value as a producer bug, not a legitimate case to silently paper over.
 */
@DomainEvent
data class BpmnReadinessAssessedEvent(
    val request: BpmnRequest,
    val assessment: ProcessInputAssessment,
    val processId: String? = null,
)
