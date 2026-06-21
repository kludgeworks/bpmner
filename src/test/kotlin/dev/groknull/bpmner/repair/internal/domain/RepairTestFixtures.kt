/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain

import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnEdge
import dev.groknull.bpmner.bpmn.BpmnEndEvent
import dev.groknull.bpmner.bpmn.BpmnExclusiveGateway
import dev.groknull.bpmner.bpmn.BpmnStartEvent
import dev.groknull.bpmner.bpmn.BpmnUserTask

/**
 * Shared test fixtures for repair domain tests.
 * Extracted to eliminate CPD (copy-paste) violations across test classes in this package.
 */

/** A join-fork process: two start events, an exclusive gateway that routes to two tasks. */
internal fun joinForkDefinition(processName: String = "Join Fork") = BpmnDefinition(
    processId = "Process_1",
    processName = processName,
    nodes =
    listOf(
        BpmnStartEvent("Start_1", "Start"),
        BpmnStartEvent("Start_2", "Trigger"),
        BpmnExclusiveGateway("Gateway_1", "Route?"),
        BpmnUserTask("Task_1", "Handle A"),
        BpmnUserTask("Task_2", "Handle B"),
        BpmnEndEvent("End_1", "End"),
    ),
    sequences =
    listOf(
        BpmnEdge("Flow_1", "Start_1", "Gateway_1"),
        BpmnEdge("Flow_2", "Start_2", "Gateway_1"),
        BpmnEdge("Flow_3", "Gateway_1", "Task_1", name = "Path A"),
        BpmnEdge("Flow_4", "Gateway_1", "Task_2", name = "Path B"),
        BpmnEdge("Flow_5", "Task_1", "End_1"),
        BpmnEdge("Flow_6", "Task_2", "End_1"),
    ),
)
