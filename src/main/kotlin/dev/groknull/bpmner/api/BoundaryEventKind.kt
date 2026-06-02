/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.api

/**
 * The kind of event that fires a boundary event attached to an activity:
 * a deadline (`TIMER`), a thrown business error (`ERROR`), or a raised escalation
 * (`ESCALATION`). Maps to the nested BPMN 2.0 event definition on
 * `<bpmn:boundaryEvent>` (`timerEventDefinition` / `errorEventDefinition` /
 * `escalationEventDefinition`).
 *
 * Shared across the LLM-facing flat wire types and the process contract so a contract
 * activity's boundary events drive the rendered `BpmnBoundaryEvent` definitions.
 */
enum class BoundaryEventKind {
    TIMER,
    ERROR,
    ESCALATION,
}
