/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring

import org.jmolecules.event.annotation.DomainEvent

/**
 * A background generation run ended by throwing out of the agent process rather than reaching
 * any goal.
 *
 * The platform sets a process's status — and so emits its lifecycle events — only on paths that
 * return normally. An exception raised by an action propagates straight out of the process's run
 * loop, so the run produces no terminal lifecycle event at all and anything watching it waits
 * forever. Every stage that can fail should reach a typed terminal instead; this event is the
 * backstop for the ones that do not, so a run always ends with a reason.
 *
 * [processId] is captured by the producer when the run is started, not resolved later by a
 * listener, because no agent-process context is in scope once the failure surfaces.
 */
@DomainEvent
data class BpmnRunAbortedEvent(
    val processId: String,
    val detail: String,
)
