/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.alignment.internal.domain

import dev.groknull.bpmner.alignment.BpmnDefinitionSummary
import dev.groknull.bpmner.bpmn.BpmnAssociation
import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnEdge
import dev.groknull.bpmner.bpmn.BpmnEndEvent
import dev.groknull.bpmner.bpmn.BpmnErrorRef
import dev.groknull.bpmner.bpmn.BpmnEscalationRef
import dev.groknull.bpmner.bpmn.BpmnGroup
import dev.groknull.bpmner.bpmn.BpmnLane
import dev.groknull.bpmner.bpmn.BpmnMessageFlow
import dev.groknull.bpmner.bpmn.BpmnMessageRef
import dev.groknull.bpmner.bpmn.BpmnParticipant
import dev.groknull.bpmner.bpmn.BpmnSignalRef
import dev.groknull.bpmner.bpmn.BpmnStartEvent
import dev.groknull.bpmner.bpmn.BpmnTextAnnotation
import dev.groknull.bpmner.bpmn.BpmnUserTask
import kotlin.reflect.full.memberProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Metamorphic coverage guard for the alignment projection.
 *
 * The summary is the only view of the generated diagram the alignment model sees, so any
 * `BpmnDefinition` field the summary drops is a field the check cannot flag. Rather than assert
 * "the summary contains X" field by field — a list that rots the moment someone adds field 20 —
 * this asserts a *relation*: perturbing a field must change the summary. If it does not, the
 * projection is structurally blind to that field.
 *
 * That framing needs no oracle. We do not have to know what the correct summary is, only that the
 * summary must respond to the input. Any field that fails to respond must be explicitly declared in
 * [BpmnDefinitionSummary.OMITTED_DEFINITION_FIELDS] with a reason, so the two together partition
 * `BpmnDefinition` exhaustively and a new field cannot silently bypass the gate — see issue #744.
 */
class BpmnSummarizerCoverageTest {
    private val summarizer = BpmnSummarizer()

    private fun base() = BpmnDefinition(
        processId = "Process_1",
        processName = "Reference process",
        nodes = listOf(
            BpmnStartEvent("Start_1", "Start"),
            BpmnUserTask("Task_1", "Do work"),
            BpmnEndEvent("End_1", "Done"),
        ),
        sequences = listOf(
            BpmnEdge("Flow_1", "Start_1", "Task_1"),
            BpmnEdge("Flow_2", "Task_1", "End_1"),
        ),
    )

    /** One perturbation per `BpmnDefinition` field: a minimal, semantically meaningful change. */
    private val perturbations: Map<String, (BpmnDefinition) -> BpmnDefinition> = mapOf(
        "processId" to { d -> d.copy(processId = "${d.processId}_x") },
        "processName" to { d -> d.copy(processName = "${d.processName} x") },
        "nodes" to { d -> d.copy(nodes = d.nodes + BpmnUserTask("Task_2", "Extra work")) },
        "sequences" to { d -> d.copy(sequences = d.sequences + BpmnEdge("Flow_3", "Task_1", "End_1")) },
        "messages" to { d -> d.copy(messages = d.messages + BpmnMessageRef("Message_1", "order placed")) },
        "errors" to { d -> d.copy(errors = d.errors + BpmnErrorRef("Error_1", "CREDIT_REJECTED")) },
        "signals" to { d -> d.copy(signals = d.signals + BpmnSignalRef("Signal_1", "settled")) },
        "escalations" to { d ->
            d.copy(escalations = d.escalations + BpmnEscalationRef("Escalation_1", "OVERDUE"))
        },
        "annotations" to { d ->
            d.copy(annotations = d.annotations + BpmnTextAnnotation("TextAnnotation_1", "For each line item"))
        },
        "groups" to { d -> d.copy(groups = d.groups + BpmnGroup("Group_1", "Phase one")) },
        "associations" to { d ->
            d.copy(associations = d.associations + BpmnAssociation("Association_1", "Task_1", "TextAnnotation_1"))
        },
        "participants" to { d ->
            d.copy(participants = d.participants + BpmnParticipant("Participant_1", "Supplier"))
        },
        "lanes" to { d ->
            d.copy(lanes = d.lanes + BpmnLane("Lane_1", "Warehouse", "Participant_1", listOf("Task_1")))
        },
        "messageFlows" to { d ->
            d.copy(
                messageFlows = d.messageFlows +
                    BpmnMessageFlow("MessageFlow_1", "Task_1", "Participant_1", "Order"),
            )
        },
        "diagramCount" to { d -> d.copy(diagramCount = d.diagramCount + 1) },
        "dataObjectReferences" to { d -> d },
        "dataStoreReferences" to { d -> d },
        "dataInputAssociations" to { d -> d },
        "dataOutputAssociations" to { d -> d },
    )

    @Test
    fun `every BpmnDefinition field is either visible to the summary or declared omitted`() {
        val declaredOmitted = BpmnDefinitionSummary.OMITTED_DEFINITION_FIELDS.keys
        val allFields = BpmnDefinition::class.memberProperties.map { it.name }.toSet()

        val blindSpots = allFields.filter { field ->
            if (field in declaredOmitted) return@filter false
            val perturb = perturbations[field]
                ?: error(
                    "BpmnDefinition.$field has no perturbation in this test. Add one, or declare the " +
                        "field in BpmnDefinitionSummary.OMITTED_DEFINITION_FIELDS with a reason.",
                )
            val original = base()
            summarizer.summarize(original) == summarizer.summarize(perturb(original))
        }

        assertTrue(
            blindSpots.isEmpty(),
            "the alignment summary does not respond to these BpmnDefinition fields, so the aligner " +
                "cannot detect changes to them: $blindSpots. Carry them in BpmnDefinitionSummary, or " +
                "declare them in OMITTED_DEFINITION_FIELDS with a reason.",
        )
    }

    @Test
    fun `the omission registry names only fields that actually exist`() {
        val allFields = BpmnDefinition::class.memberProperties.map { it.name }.toSet()
        val phantom = BpmnDefinitionSummary.OMITTED_DEFINITION_FIELDS.keys - allFields

        assertEquals(
            emptySet(),
            phantom,
            "OMITTED_DEFINITION_FIELDS names fields that no longer exist on BpmnDefinition — the " +
                "registry has rotted and is no longer proving anything",
        )
    }

    @Test
    fun `every declared omission carries a reason`() {
        BpmnDefinitionSummary.OMITTED_DEFINITION_FIELDS.forEach { (field, reason) ->
            assertTrue(reason.isNotBlank(), "omission of BpmnDefinition.$field has no stated reason")
        }
    }
}
