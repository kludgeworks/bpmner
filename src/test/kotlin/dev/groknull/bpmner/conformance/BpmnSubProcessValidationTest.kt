/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.conformance

import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnEdge
import dev.groknull.bpmner.bpmn.BpmnEndEvent
import dev.groknull.bpmner.bpmn.BpmnErrorEventDefinition
import dev.groknull.bpmner.bpmn.BpmnErrorRef
import dev.groknull.bpmner.bpmn.BpmnEventSubProcess
import dev.groknull.bpmner.bpmn.BpmnStartEvent
import dev.groknull.bpmner.bpmn.BpmnSubProcess
import dev.groknull.bpmner.bpmn.BpmnUserTask
import dev.groknull.bpmner.conformance.internal.domain.BpmnDefinitionValidator
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * Subprocess containment validation: each subprocess declares its own start/end, every parentRef
 * resolves to an actual subprocess, sequence flows stay within one scope, and parentRef chains are
 * acyclic. These turn malformed input into clean errors instead of a renderer crash.
 */
class BpmnSubProcessValidationTest {
    private val validator = BpmnDefinitionValidator()

    @Test
    fun `validator accepts a subprocess that declares its own start and end`() {
        val definition =
            BpmnDefinition(
                processId = "Process_1",
                processName = "Handle request",
                nodes =
                listOf(
                    BpmnStartEvent("StartEvent_1", "Received"),
                    BpmnSubProcess("SubProcess_1", "Handle"),
                    BpmnStartEvent("Inner_start", "Begin", parentRef = "SubProcess_1"),
                    BpmnUserTask("Inner_task", "Work", parentRef = "SubProcess_1"),
                    BpmnEndEvent("Inner_end", "Inner done", parentRef = "SubProcess_1"),
                    BpmnEndEvent("EndEvent_1", "Completed"),
                ),
                sequences =
                listOf(
                    BpmnEdge("Flow_1", "StartEvent_1", "SubProcess_1"),
                    BpmnEdge("Flow_2", "SubProcess_1", "EndEvent_1"),
                    BpmnEdge("Flow_in1", "Inner_start", "Inner_task", parentRef = "SubProcess_1"),
                    BpmnEdge("Flow_in2", "Inner_task", "Inner_end", parentRef = "SubProcess_1"),
                ),
            )

        val errors = validator.validate(definition)
        assertTrue(errors.isEmpty(), "Expected no validation errors, got: $errors")
    }

    @Test
    fun `validator rejects a subprocess missing its own start and end`() {
        val definition =
            BpmnDefinition(
                processId = "Process_1",
                processName = "Handle request",
                nodes =
                listOf(
                    BpmnStartEvent("StartEvent_1", "Received"),
                    BpmnSubProcess("SubProcess_1", "Handle"),
                    BpmnUserTask("Inner_task", "Work", parentRef = "SubProcess_1"),
                    BpmnEndEvent("EndEvent_1", "Completed"),
                ),
                sequences =
                listOf(
                    BpmnEdge("Flow_1", "StartEvent_1", "SubProcess_1"),
                    BpmnEdge("Flow_2", "SubProcess_1", "EndEvent_1"),
                ),
            )

        val errors = validator.validate(definition).joinToString("\n")
        assertContains(errors, "subprocess 'SubProcess_1' must contain at least one START_EVENT")
        assertContains(errors, "subprocess 'SubProcess_1' must contain at least one END_EVENT")
    }

    @Test
    fun `validator rejects a parentRef that does not resolve or points to a non-subprocess`() {
        val definition =
            BpmnDefinition(
                processId = "Process_1",
                processName = "Handle request",
                nodes =
                listOf(
                    BpmnStartEvent("StartEvent_1", "Received"),
                    BpmnUserTask("Task_anchor", "Anchor"),
                    // parentRef names an existing node that is not a subprocess.
                    BpmnUserTask("Task_bad_type", "Bad type", parentRef = "Task_anchor"),
                    // parentRef names a node that does not exist.
                    BpmnUserTask("Task_bad_ref", "Bad ref", parentRef = "Nope"),
                    BpmnEndEvent("EndEvent_1", "Completed"),
                ),
                sequences =
                listOf(
                    BpmnEdge("Flow_1", "StartEvent_1", "Task_anchor"),
                    BpmnEdge("Flow_2", "Task_anchor", "EndEvent_1"),
                ),
            )

        val errors = validator.validate(definition).joinToString("\n")
        assertContains(errors, "node 'Task_bad_type' parentRef 'Task_anchor' must reference a subprocess")
        assertContains(errors, "node 'Task_bad_ref' parentRef 'Nope' does not match any node id")
    }

    @Test
    fun `validator rejects a sequence flow that crosses a subprocess boundary`() {
        val definition =
            BpmnDefinition(
                processId = "Process_1",
                processName = "Handle request",
                nodes =
                listOf(
                    BpmnStartEvent("StartEvent_1", "Received"),
                    BpmnSubProcess("SubProcess_1", "Handle"),
                    BpmnStartEvent("Inner_start", "Begin", parentRef = "SubProcess_1"),
                    BpmnEndEvent("Inner_end", "Inner done", parentRef = "SubProcess_1"),
                    BpmnEndEvent("EndEvent_1", "Completed"),
                ),
                sequences =
                listOf(
                    BpmnEdge("Flow_1", "StartEvent_1", "SubProcess_1"),
                    BpmnEdge("Flow_2", "SubProcess_1", "EndEvent_1"),
                    // Top-level edge reaching into the subprocess's inner node — crosses the boundary.
                    BpmnEdge("Flow_cross", "StartEvent_1", "Inner_start"),
                    BpmnEdge("Flow_in", "Inner_start", "Inner_end", parentRef = "SubProcess_1"),
                ),
            )

        val errors = validator.validate(definition).joinToString("\n")
        assertContains(errors, "edge 'Flow_cross' must not cross a subprocess boundary")
    }

    @Test
    fun `validator accepts a floating event subprocess with no incoming or outgoing sequence flow`() {
        // V3 (#622-Y): an event subprocess is triggered by its own start event, never by flow —
        // requiring incoming/outgoing sequence flow, or weak connectivity to its sibling scope,
        // would reject every valid event subprocess.
        val definition =
            BpmnDefinition(
                processId = "Process_1",
                processName = "Handle request",
                nodes =
                listOf(
                    BpmnStartEvent("StartEvent_1", "Received"),
                    BpmnUserTask("Task_work", "Work"),
                    BpmnEndEvent("EndEvent_1", "Completed"),
                    BpmnEventSubProcess("SubProcess_handler", "Handle error"),
                    BpmnStartEvent(
                        "Inner_start",
                        "On error",
                        eventDefinition = BpmnErrorEventDefinition("Error_1"),
                        parentRef = "SubProcess_handler",
                    ),
                    BpmnEndEvent("Inner_end", "Handled", parentRef = "SubProcess_handler"),
                ),
                sequences =
                listOf(
                    BpmnEdge("Flow_1", "StartEvent_1", "Task_work"),
                    BpmnEdge("Flow_2", "Task_work", "EndEvent_1"),
                    BpmnEdge("Flow_in", "Inner_start", "Inner_end", parentRef = "SubProcess_handler"),
                ),
                errors = listOf(BpmnErrorRef(id = "Error_1", code = "ERR")),
            )

        val errors = validator.validate(definition)
        assertTrue(errors.isEmpty(), "Expected no validation errors, got: $errors")
    }

    @Test
    fun `validator rejects an event subprocess whose start event has no trigger`() {
        // A5 (#622-Y): an event subprocess is triggered by its start event; a NONE start cannot
        // trigger anything, leaving the scope unreachable.
        val definition =
            BpmnDefinition(
                processId = "Process_1",
                processName = "Handle request",
                nodes =
                listOf(
                    BpmnStartEvent("StartEvent_1", "Received"),
                    BpmnUserTask("Task_work", "Work"),
                    BpmnEndEvent("EndEvent_1", "Completed"),
                    BpmnEventSubProcess("SubProcess_handler", "Handle error"),
                    BpmnStartEvent("Inner_start", "Begin", parentRef = "SubProcess_handler"),
                    BpmnEndEvent("Inner_end", "Handled", parentRef = "SubProcess_handler"),
                ),
                sequences =
                listOf(
                    BpmnEdge("Flow_1", "StartEvent_1", "Task_work"),
                    BpmnEdge("Flow_2", "Task_work", "EndEvent_1"),
                    BpmnEdge("Flow_in", "Inner_start", "Inner_end", parentRef = "SubProcess_handler"),
                ),
            )

        val errors = validator.validate(definition).joinToString("\n")
        assertContains(
            errors,
            "event subprocess 'SubProcess_handler' start event must declare a triggering event definition",
        )
    }

    @Test
    fun `validator rejects a cyclic subprocess parentRef chain`() {
        val definition =
            BpmnDefinition(
                processId = "Process_1",
                processName = "Handle request",
                nodes =
                listOf(
                    BpmnStartEvent("StartEvent_1", "Received"),
                    BpmnEndEvent("EndEvent_1", "Completed"),
                    // Two subprocesses naming each other as parent.
                    BpmnSubProcess("SubProcess_A", "A", parentRef = "SubProcess_B"),
                    BpmnSubProcess("SubProcess_B", "B", parentRef = "SubProcess_A"),
                ),
                sequences = listOf(BpmnEdge("Flow_1", "StartEvent_1", "EndEvent_1")),
            )

        val errors = validator.validate(definition).joinToString("\n")
        assertContains(errors, "has a cyclic parentRef chain")
    }
}
