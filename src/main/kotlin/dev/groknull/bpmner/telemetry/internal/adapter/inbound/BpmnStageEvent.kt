/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.telemetry.internal.adapter.inbound

import com.embabel.agent.api.event.AbstractAgentProcessEvent
import com.embabel.agent.core.AgentProcess

/**
 * Published alongside [com.embabel.agent.api.event.ProgressUpdateEvent] when an @Action starts,
 * carrying a stable machine-readable stage identifier and status for the six-chip pipeline rail.
 *
 * Wire contract (ARCHITECTURE.md §wire-contract): the Kotlin simple class name is the SSE `type`
 * discriminator; class name and property names are API — do not rename without a client update.
 *
 * Stage keys: `readiness | contract | generate | validate | layout | align`
 * Status values in `stageStatus`: `active | done | warn`
 *
 * Note: the property is named `stageStatus` (not `status`) because
 * `AbstractAgentProcessEvent` already exposes a `status` getter returning
 * `AgentProcessStatusReport`; using `status` here would hide it and cause
 * a Kotlin compiler error for incompatible types.
 */
class BpmnStageEvent(
    process: AgentProcess,
    val stage: String,
    val stageStatus: String,
    val label: String,
) : AbstractAgentProcessEvent(process)
