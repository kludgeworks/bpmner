/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout

import org.jmolecules.event.annotation.DomainEvent

/**
 * Published when server-side auto-layout completes and the DI-bearing XML re-enters the
 * pipeline inside [dev.groknull.bpmner.pipeline.internal.adapter.inbound.BpmnGenerationAgent].
 * Consumed by [dev.groknull.bpmner.pipeline.internal.adapter.inbound.BpmnRunUpdateChannel] to
 * emit a `LAYOUT` [dev.groknull.bpmner.pipeline.RunUpdate] over bpmner's own SSE endpoint
 * (epic #605; supersedes the deleted `BpmnSnapshotEvent`/ADR-ss-007 mechanism this event used
 * to feed).
 *
 * Lives at the layout module root (published API) following the same pattern as
 * [dev.groknull.bpmner.authoring.BpmnGeneratedEvent] and
 * [dev.groknull.bpmner.conformance.BpmnValidationPassedEvent].
 */
@DomainEvent
data class BpmnLayoutCompletedEvent(
    val xml: String,
)
