/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract

import org.jmolecules.event.annotation.DomainEvent

/**
 * [processId] is captured by the producer via `AgentProcess.get()?.id` at publish time (inside
 * the `extract` `@Action`), not resolved later by a listener — see
 * [dev.groknull.bpmner.authoring.BpmnGeneratedEvent] for why publish-time capture is the robust
 * point.
 */
@DomainEvent
data class BpmnContractExtractedEvent(
    val contract: ValidatedProcessContract,
    val processId: String? = null,
)
