/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.bpmn

/**
 * The kind of event that fires a boundary event attached to an activity: a deadline (`TIMER`),
 * a thrown business error (`ERROR`), a raised business escalation (`ESCALATION`), or an
 * asynchronous message from another party (`MESSAGE`). Maps to the nested BPMN 2.0 event
 * definition on `<bpmn:boundaryEvent>` (`timerEventDefinition` / `errorEventDefinition` /
 * `escalationEventDefinition` / `messageEventDefinition`).
 *
 * Shared across the LLM-facing flat wire types and the process contract so a contract
 * activity's boundary events drive the rendered `BpmnBoundaryEvent` definitions.
 */
enum class BoundaryEventKind {
    TIMER,
    ERROR,
    ESCALATION,
    MESSAGE,
}
