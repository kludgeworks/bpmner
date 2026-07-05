/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout

import org.jmolecules.event.annotation.DomainEvent

/**
 * Published when server-side auto-layout completes and the DI-bearing XML re-enters the
 * pipeline inside [dev.groknull.bpmner.pipeline.internal.adapter.inbound.BpmnGenerationAgent].
 * Consumed by telemetry to emit a `LAYOUT_COMPLETE` [dev.groknull.bpmner.telemetry.BpmnSnapshotEvent]
 * over the Embabel SSE channel, allowing the web client to switch from its client-side
 * preview layout to the authoritative server geometry (ARCH ADR-ss-007).
 *
 * Lives at the layout module root (published API) following the same pattern as
 * [dev.groknull.bpmner.authoring.BpmnGeneratedEvent] and
 * [dev.groknull.bpmner.conformance.BpmnValidationPassedEvent].
 */
@DomainEvent
data class BpmnLayoutCompletedEvent(
    val xml: String,
)
