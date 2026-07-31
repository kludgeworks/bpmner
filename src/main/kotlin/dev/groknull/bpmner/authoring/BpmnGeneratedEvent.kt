/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring

import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.bpmn.RenderedBpmn
import org.jmolecules.event.annotation.DomainEvent

/**
 * [processId] is captured by the producer via `AgentProcess.get()?.id` at publish time (inside
 * the `render` `@Action`), not resolved later by a listener — this is the correct point to
 * capture it regardless of `SimpleAgentProcess` vs `ConcurrentAgentProcess`, and regardless of
 * whether a future listener is synchronous or `@Async`.
 */
@DomainEvent
data class BpmnGeneratedEvent(
    val request: BpmnRequest,
    val rendered: RenderedBpmn,
    val processId: String? = null,
)
