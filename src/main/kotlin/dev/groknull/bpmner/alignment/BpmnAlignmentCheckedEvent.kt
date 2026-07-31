/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.alignment

import dev.groknull.bpmner.alignment.BpmnAlignmentReport
import dev.groknull.bpmner.bpmn.BpmnRequest
import org.jmolecules.event.annotation.DomainEvent

/**
 * [processId] is captured by the producer via `AgentProcess.get()?.id` at publish time (inside
 * the `align` `@Action`), not resolved later by a listener — see [dev.groknull.bpmner.authoring.BpmnGeneratedEvent]
 * for why publish-time capture is the robust point.
 */
@DomainEvent
data class BpmnAlignmentCheckedEvent(
    val request: BpmnRequest,
    val report: BpmnAlignmentReport,
    val processId: String? = null,
)
