/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring

import dev.groknull.bpmner.bpmn.LaidOutProcessGraph
import org.jmolecules.event.annotation.DomainEvent

/**
 * [processId] is captured by the producer via `AgentProcess.get()?.id` at publish time (inside
 * the `composeGraph` `@Action`), not resolved later by a listener — see
 * [BpmnGeneratedEvent] for why publish-time capture is the robust point.
 */
@DomainEvent
data class BpmnGraphComposedEvent(
    val graph: LaidOutProcessGraph,
    val corrections: List<ContractCorrection> = emptyList(),
    val processId: String? = null,
)
