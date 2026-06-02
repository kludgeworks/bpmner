/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.validation

import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnSubProcess
import dev.groknull.bpmner.core.BpmnUserTask
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
