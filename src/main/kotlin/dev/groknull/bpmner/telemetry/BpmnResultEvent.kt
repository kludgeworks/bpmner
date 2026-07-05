/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.telemetry

import com.embabel.agent.api.event.AbstractAgentProcessEvent
import com.embabel.agent.core.AgentProcess

/**
 * Published when a BPMN generation run reaches a terminal [dev.groknull.bpmner.authoring.BpmnGenerationStatus],
 * carrying the machine-readable status, alignment verdict, and alignment rationale so the web client
 * can display a typed result bar and offer a canonical XML download (ARCHITECTURE.md §ss-3, ADR-ss-004).
 *
 * Wire contract (docs/architecture.md §wire-contract): the Kotlin simple class name `"BpmnResultEvent"`
 * is the SSE `type` discriminator; class name and property names are API — do not rename without a
 * client update.
 *
 * The property is named `resultStatus` (not `status`) because [AbstractAgentProcessEvent] already
 * exposes a `status` getter returning `AgentProcessStatusReport`; a subclass property literally
 * named `status` would fail to compile with an incompatible-type override error (ADR-ss-008,
 * as-built precedent from ss-1's `BpmnStageEvent.stageStatus`).
 *
 * Status values in `resultStatus`: `GENERATED | NEEDS_CLARIFICATION | ALIGNMENT_FAILED | VALIDATION_FAILED`
 * Verdict values in `alignmentVerdict`: `ALIGNED | PARTIALLY_ALIGNED | FAILED` (null when no alignment ran)
 * `alignmentReport` is the human-readable rationale from [dev.groknull.bpmner.alignment.BpmnAlignmentReport.rationale]
 * (null when no alignment ran or rationale is absent).
 */
class BpmnResultEvent(
    process: AgentProcess,
    val resultStatus: String,
    val alignmentVerdict: String?,
    val alignmentReport: String?,
) : AbstractAgentProcessEvent(process)
