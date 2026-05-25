/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.api

/**
 * Discrimination for a BPMN timer event definition: a fixed date, a relative duration, or
 * a recurring cycle. Mirrors the BPMN 2.0 `timerEventDefinition` content types.
 */
enum class BpmnTimerKind {
    DATE,
    DURATION,
    CYCLE,
}
