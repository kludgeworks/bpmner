/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.bpmn

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * Unit tests for [BpmnDefinition.validateStructure].
 *
 * Proves that the model-intrinsic structural checks (duplicate ids, edge reference integrity,
 * required top-level START/END events) live on the domain type itself — following the
 * [LaidOutProcessGraph.validateOwnership] idiom — and that [dev.groknull.bpmner.conformance
 * .BpmnDefinitionValidator] delegates to this method rather than duplicating the predicate logic.
 */
class BpmnDefinitionValidateStructureTest {

    // --- helpers ---

    private fun minimalValid(): BpmnDefinition = BpmnDefinition(
        processId = "Process_1",
        processName = "Test process",
        nodes =
        listOf(
            BpmnStartEvent(id = "Start_1", name = "Start"),
            BpmnUserTask(id = "Task_1", name = "Do the thing"),
            BpmnEndEvent(id = "End_1", name = "End"),
        ),
        sequences =
        listOf(
            BpmnEdge(id = "Flow_1", sourceRef = "Start_1", targetRef = "Task_1"),
            BpmnEdge(id = "Flow_2", sourceRef = "Task_1", targetRef = "End_1"),
        ),
    )

    // --- happy path ---

    @Test
    fun `validateStructure returns empty list for a structurally valid definition`() {
        val errors = minimalValid().validateStructure()
        assertTrue(errors.isEmpty(), "Expected no errors but got: $errors")
    }

    // --- duplicate id checks ---

    @Test
    fun `validateStructure detects duplicate node ids`() {
        val definition =
            minimalValid().copy(
                nodes =
                listOf(
                    BpmnStartEvent(id = "Start_1", name = "Start"),
                    BpmnUserTask(id = "Start_1", name = "Duplicate id task"),
                    BpmnEndEvent(id = "End_1", name = "End"),
                ),
            )
        val errors = definition.validateStructure()
        assertTrue(errors.any { it.contains("duplicate node id") }, "Expected duplicate node id error, got: $errors")
        assertContains(errors.joinToString(), "Start_1")
    }

    @Test
    fun `validateStructure detects duplicate edge ids`() {
        val definition =
            minimalValid().copy(
                sequences =
                listOf(
                    BpmnEdge(id = "Flow_1", sourceRef = "Start_1", targetRef = "Task_1"),
                    BpmnEdge(id = "Flow_1", sourceRef = "Task_1", targetRef = "End_1"),
                ),
            )
        val errors = definition.validateStructure()
        assertTrue(errors.any { it.contains("duplicate edge id") }, "Expected duplicate edge id error, got: $errors")
        assertContains(errors.joinToString(), "Flow_1")
    }

    // --- edge reference integrity checks ---

    @Test
    fun `validateStructure detects edge with unresolved sourceRef`() {
        val definition =
            minimalValid().copy(
                sequences =
                listOf(
                    BpmnEdge(id = "Flow_1", sourceRef = "NoSuchNode", targetRef = "Task_1"),
                    BpmnEdge(id = "Flow_2", sourceRef = "Task_1", targetRef = "End_1"),
                ),
            )
        val errors = definition.validateStructure()
        assertTrue(
            errors.any { it.contains("sourceRef") && it.contains("NoSuchNode") },
            "Expected sourceRef resolution error, got: $errors",
        )
    }

    @Test
    fun `validateStructure detects edge with unresolved targetRef`() {
        val definition =
            minimalValid().copy(
                sequences =
                listOf(
                    BpmnEdge(id = "Flow_1", sourceRef = "Start_1", targetRef = "NoSuchNode"),
                    BpmnEdge(id = "Flow_2", sourceRef = "Task_1", targetRef = "End_1"),
                ),
            )
        val errors = definition.validateStructure()
        assertTrue(
            errors.any { it.contains("targetRef") && it.contains("NoSuchNode") },
            "Expected targetRef resolution error, got: $errors",
        )
    }

    @Test
    fun `validateStructure detects self-referencing edge`() {
        val definition =
            minimalValid().copy(
                sequences =
                listOf(
                    BpmnEdge(id = "Flow_1", sourceRef = "Start_1", targetRef = "Start_1"),
                    BpmnEdge(id = "Flow_2", sourceRef = "Task_1", targetRef = "End_1"),
                ),
            )
        val errors = definition.validateStructure()
        assertTrue(
            errors.any { it.contains("self-reference") },
            "Expected self-reference error, got: $errors",
        )
    }

    // --- required events checks ---

    @Test
    fun `validateStructure detects missing top-level START_EVENT`() {
        val definition =
            minimalValid().copy(
                nodes =
                listOf(
                    BpmnUserTask(id = "Task_1", name = "Do the thing"),
                    BpmnEndEvent(id = "End_1", name = "End"),
                ),
            )
        val errors = definition.validateStructure()
        assertTrue(
            errors.any { it.contains("START_EVENT") },
            "Expected missing START_EVENT error, got: $errors",
        )
    }

    @Test
    fun `validateStructure detects missing top-level END_EVENT`() {
        val definition =
            minimalValid().copy(
                nodes =
                listOf(
                    BpmnStartEvent(id = "Start_1", name = "Start"),
                    BpmnUserTask(id = "Task_1", name = "Do the thing"),
                ),
            )
        val errors = definition.validateStructure()
        assertTrue(
            errors.any { it.contains("END_EVENT") },
            "Expected missing END_EVENT error, got: $errors",
        )
    }

    @Test
    fun `validateStructure does not flag nested START and END events as top-level violations`() {
        // A subprocess has its own start/end; top-level still has one START and one END.
        val subProcess = BpmnSubProcess(id = "Sub_1", name = "Sub process")
        val innerStart = BpmnStartEvent(id = "InnerStart_1", name = "Inner start", parentRef = "Sub_1")
        val innerEnd = BpmnEndEvent(id = "InnerEnd_1", name = "Inner end", parentRef = "Sub_1")
        val definition =
            BpmnDefinition(
                processId = "Process_1",
                processName = "Nested process",
                nodes =
                listOf(
                    BpmnStartEvent(id = "Start_1", name = "Start"),
                    subProcess,
                    innerStart,
                    innerEnd,
                    BpmnEndEvent(id = "End_1", name = "End"),
                ),
                sequences =
                listOf(
                    BpmnEdge(id = "Flow_1", sourceRef = "Start_1", targetRef = "Sub_1"),
                    BpmnEdge(id = "Flow_2", sourceRef = "Sub_1", targetRef = "End_1"),
                    BpmnEdge(id = "InnerFlow_1", sourceRef = "InnerStart_1", targetRef = "InnerEnd_1", parentRef = "Sub_1"),
                ),
            )
        val errors = definition.validateStructure()
        assertTrue(
            errors.none { it.contains("START_EVENT") || it.contains("END_EVENT") },
            "Should not flag nested events as missing top-level START/END, got: $errors",
        )
    }

    // --- data association reference checks ---

    @Test
    fun `validateStructure accepts data input and output associations with resolvable references`() {
        val definition = minimalValid().copy(
            dataObjectReferences = listOf(BpmnDataObjectReference(id = "DataObjectRef_1", name = "Order")),
            dataInputAssociations = listOf(
                BpmnDataInputAssociation(id = "DataInputAssociation_1", sourceRef = "DataObjectRef_1", activityRef = "Task_1"),
            ),
            dataOutputAssociations = listOf(
                BpmnDataOutputAssociation(id = "DataOutputAssociation_1", activityRef = "Task_1", targetRef = "DataObjectRef_1"),
            ),
        )
        val errors = definition.validateStructure()
        assertTrue(errors.isEmpty(), "Expected no errors but got: $errors")
    }

    @Test
    fun `validateStructure detects a data input association whose activityRef does not resolve`() {
        val definition = minimalValid().copy(
            dataObjectReferences = listOf(BpmnDataObjectReference(id = "DataObjectRef_1")),
            dataInputAssociations = listOf(
                BpmnDataInputAssociation(id = "DataInputAssociation_1", sourceRef = "DataObjectRef_1", activityRef = "Ghost"),
            ),
        )
        val errors = definition.validateStructure()
        assertContains(errors.joinToString(), "activityRef 'Ghost' does not match any node id")
    }

    @Test
    fun `validateStructure detects a data input association whose sourceRef does not resolve`() {
        val definition = minimalValid().copy(
            dataInputAssociations = listOf(
                BpmnDataInputAssociation(id = "DataInputAssociation_1", sourceRef = "Ghost", activityRef = "Task_1"),
            ),
        )
        val errors = definition.validateStructure()
        assertContains(errors.joinToString(), "sourceRef 'Ghost' does not match any data object/store reference id")
    }

    @Test
    fun `validateStructure detects a data output association whose targetRef does not resolve`() {
        val definition = minimalValid().copy(
            dataOutputAssociations = listOf(
                BpmnDataOutputAssociation(id = "DataOutputAssociation_1", activityRef = "Task_1", targetRef = "Ghost"),
            ),
        )
        val errors = definition.validateStructure()
        assertContains(errors.joinToString(), "targetRef 'Ghost' does not match any data object/store reference id")
    }

    // --- return idiom (no throw) ---

    @Test
    fun `validateStructure never throws even for a maximally broken definition`() {
        val definition =
            BpmnDefinition(
                processId = "Process_broken",
                processName = "Broken",
                nodes =
                listOf(
                    // Duplicate ids, no start, no end
                    BpmnUserTask(id = "DupId", name = "First"),
                    BpmnUserTask(id = "DupId", name = "Second"),
                ),
                sequences =
                listOf(
                    // Self-reference and unresolved refs
                    BpmnEdge(id = "Flow_1", sourceRef = "DupId", targetRef = "DupId"),
                    BpmnEdge(id = "Flow_1", sourceRef = "Ghost", targetRef = "Ghost"),
                ),
            )
        // Must not throw — returns a list of errors
        val errors = definition.validateStructure()
        assertTrue(errors.isNotEmpty(), "Expected errors for a broken definition, got none")
    }
}
