/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring

// The seven BPMN task element local names. Any task kind may carry a multi-instance marker, so
// the renderer (stamping it) and the parser (reading it back) iterate this single list — kept in
// one place so the two sides cannot drift when a new task kind is added.
internal val BPMN_TASK_LOCAL_NAMES = listOf(
    "userTask",
    "serviceTask",
    "scriptTask",
    "manualTask",
    "businessRuleTask",
    "sendTask",
    "receiveTask",
)
