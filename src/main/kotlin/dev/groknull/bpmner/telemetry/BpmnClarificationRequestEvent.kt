/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.telemetry

import com.embabel.agent.api.event.AbstractAgentProcessEvent
import com.embabel.agent.core.AgentProcess

/**
 * Published when a BPMN generation run parks in `AwaitingClarification`, carrying the round number,
 * total rounds allowed, and the human-readable question text (ARCHITECTURE.md §ss-4, ADR-ss-003).
 *
 * Wire contract (docs/architecture.md §wire-contract): the Kotlin simple class name
 * `"BpmnClarificationRequestEvent"` is the SSE `type` discriminator; class name and property
 * names are API — do not rename without a client update.
 *
 * Fields use `round`/`maxRounds`/`prompt` (not `status` — ADR-ss-008: `AbstractAgentProcessEvent`
 * already exposes a `status` getter returning `AgentProcessStatusReport`).
 *
 * The `prompt` is sourced from `process.last(FormBindingRequest::class.java).payload.title` — the
 * `FormBindingRequest` is re-pushed on every park, so this is always the current-round question,
 * not a stale re-assessment. See PLAN-ss-4.md §Design decisions for why `FormBindingRequest.payload`
 * is preferred over `process.last(ProcessInputAssessment)`.
 *
 * No bpmner payload types are referenced; fields are primitives and framework types only.
 */
class BpmnClarificationRequestEvent(
    process: AgentProcess,
    val round: Int,
    val maxRounds: Int,
    val prompt: String,
) : AbstractAgentProcessEvent(process)
