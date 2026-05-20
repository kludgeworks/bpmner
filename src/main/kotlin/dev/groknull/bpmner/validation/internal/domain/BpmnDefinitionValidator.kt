/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.validation.internal.domain

import dev.groknull.bpmner.core.BpmnBoundaryEvent
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnErrorEventDefinition
import dev.groknull.bpmner.core.BpmnEscalationEventDefinition
import dev.groknull.bpmner.core.BpmnEventDefinition
import dev.groknull.bpmner.core.BpmnIntermediateCatchEvent
import dev.groknull.bpmner.core.BpmnIntermediateThrowEvent
import dev.groknull.bpmner.core.BpmnMessageEventDefinition
import dev.groknull.bpmner.core.BpmnNodeNamingPolicy
import dev.groknull.bpmner.core.BpmnNoneEventDefinition
import dev.groknull.bpmner.core.BpmnServiceTask
import dev.groknull.bpmner.core.BpmnSignalEventDefinition
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnTerminateEventDefinition
import dev.groknull.bpmner.core.BpmnTimerEventDefinition
import dev.groknull.bpmner.core.BpmnUserTask
import org.jmolecules.ddd.annotation.Service
import org.springframework.stereotype.Component

@Service
@Component
internal class BpmnDefinitionValidator {
    fun validate(definition: BpmnDefinition): List<String> {
        val errors = mutableListOf<String>()

        validateDuplicateIds(definition, errors)
        validateNames(definition, errors)
        validateEdges(definition, errors)
        validateRequiredEvents(definition, errors)
        validateEventDefinitions(definition, errors)

        return errors
    }

    private fun validateDuplicateIds(
        definition: BpmnDefinition,
        errors: MutableList<String>,
    ) {
        val nodeIds = definition.nodes.map { it.id.trim() }
        val edgeIds = definition.sequences.map { it.id.trim() }

        nodeIds
            .groupBy { it }
            .filter { (id, all) -> id.isNotBlank() && all.size > 1 }
            .keys
            .forEach { errors.add("duplicate node id: $it") }

        edgeIds
            .groupBy { it }
            .filter { (id, all) -> id.isNotBlank() && all.size > 1 }
            .keys
            .forEach { errors.add("duplicate edge id: $it") }
    }

    private fun validateNames(
        definition: BpmnDefinition,
        errors: MutableList<String>,
    ) {
        val outgoingCounts = definition.sequences.groupingBy { it.sourceRef }.eachCount()

        definition.nodes.forEach { node ->
            val requiresName =
                BpmnNodeNamingPolicy.requiresName(
                    node = node,
                    outgoingCount = outgoingCounts[node.id] ?: 0,
                )
            if (requiresName && node.name.isNullOrBlank()) {
                errors.add(BpmnNodeNamingPolicy.missingNameMessage(node))
            }
        }
    }

    private fun validateEdges(
        definition: BpmnDefinition,
        errors: MutableList<String>,
    ) {
        val nodeIdSet = definition.nodes.map { it.id }.toSet()
        definition.sequences.forEach { edge ->
            if (edge.sourceRef !in nodeIdSet) {
                errors.add(
                    "edge ${edge.id.ifBlank { "<blank>" }} sourceRef '${edge.sourceRef}' does not match any node id",
                )
            }
            if (edge.targetRef !in nodeIdSet) {
                errors.add(
                    "edge ${edge.id.ifBlank { "<blank>" }} targetRef '${edge.targetRef}' does not match any node id",
                )
            }
            if (edge.sourceRef == edge.targetRef) {
                errors.add("edge ${edge.id.ifBlank { "<blank>" }} must not self-reference source and target")
            }
        }
    }

    private fun validateRequiredEvents(
        definition: BpmnDefinition,
        errors: MutableList<String>,
    ) {
        if (definition.nodes.none { it is BpmnStartEvent }) {
            errors.add("definition must contain at least one START_EVENT")
        }
        if (definition.nodes.none { it is BpmnEndEvent }) {
            errors.add("definition must contain at least one END_EVENT")
        }
    }

    @Suppress("LongMethod")
    private fun validateEventDefinitions(
        definition: BpmnDefinition,
        errors: MutableList<String>,
    ) {
        val nodesById = definition.nodes.associateBy { it.id }
        val messageIds = definition.messages.map { it.id }.toSet()
        val signalIds = definition.signals.map { it.id }.toSet()
        val errorIds = definition.errors.map { it.id }.toSet()
        val escalationIds = definition.escalations.map { it.id }.toSet()

        definition.nodes.forEach { node ->
            when (node) {
                is BpmnStartEvent -> {
                    validateEventDefinition(
                        node.id,
                        node.eventDefinition,
                        messageIds,
                        signalIds,
                        errorIds,
                        escalationIds,
                        errors,
                    )
                }

                is BpmnIntermediateCatchEvent -> {
                    if (node.eventDefinition is BpmnNoneEventDefinition) {
                        errors.add("intermediate catch event ${node.id} must declare an event definition")
                    }
                    validateEventDefinition(
                        node.id,
                        node.eventDefinition,
                        messageIds,
                        signalIds,
                        errorIds,
                        escalationIds,
                        errors,
                    )
                }

                is BpmnIntermediateThrowEvent -> {
                    if (node.eventDefinition is BpmnNoneEventDefinition) {
                        errors.add("intermediate throw event ${node.id} must declare an event definition")
                    }
                    validateEventDefinition(
                        node.id,
                        node.eventDefinition,
                        messageIds,
                        signalIds,
                        errorIds,
                        escalationIds,
                        errors,
                    )
                }

                is BpmnBoundaryEvent -> {
                    if (node.eventDefinition is BpmnNoneEventDefinition) {
                        errors.add("boundary event ${node.id} must declare an event definition")
                    }
                    val attachedTo = nodesById[node.attachedToRef]
                    if (attachedTo == null) {
                        errors.add("boundary event ${node.id} attachedToRef '${node.attachedToRef}' does not match any node id")
                    } else if (attachedTo !is BpmnUserTask && attachedTo !is BpmnServiceTask) {
                        errors.add(
                            "boundary event ${node.id} attachedToRef '${node.attachedToRef}' " +
                                "must reference an attachable activity",
                        )
                    }
                    validateEventDefinition(
                        node.id,
                        node.eventDefinition,
                        messageIds,
                        signalIds,
                        errorIds,
                        escalationIds,
                        errors,
                    )
                }

                is BpmnEndEvent -> {
                    validateEventDefinition(
                        node.id,
                        node.eventDefinition,
                        messageIds,
                        signalIds,
                        errorIds,
                        escalationIds,
                        errors,
                    )
                }

                else -> {
                    Unit
                }
            }
        }
    }

    @Suppress("LongParameterList")
    private fun validateEventDefinition(
        nodeId: String,
        eventDefinition: BpmnEventDefinition,
        messageIds: Set<String>,
        signalIds: Set<String>,
        errorIds: Set<String>,
        escalationIds: Set<String>,
        errors: MutableList<String>,
    ) {
        when (eventDefinition) {
            is BpmnNoneEventDefinition -> {
                Unit
            }

            is BpmnTimerEventDefinition -> {
                if (eventDefinition.expression.isBlank()) {
                    errors.add("event $nodeId timer definition expression must not be blank")
                }
            }

            is BpmnMessageEventDefinition -> {
                if (eventDefinition.messageRef !in messageIds) {
                    errors.add(
                        "event $nodeId messageRef '${eventDefinition.messageRef}' " +
                            "does not match any message catalog id",
                    )
                }
            }

            is BpmnSignalEventDefinition -> {
                if (eventDefinition.signalRef !in signalIds) {
                    errors.add("event $nodeId signalRef '${eventDefinition.signalRef}' does not match any signal catalog id")
                }
            }

            is BpmnErrorEventDefinition -> {
                if (eventDefinition.errorRef !in errorIds) {
                    errors.add("event $nodeId errorRef '${eventDefinition.errorRef}' does not match any error catalog id")
                }
            }

            is BpmnEscalationEventDefinition -> {
                if (eventDefinition.escalationRef !in escalationIds) {
                    errors.add(
                        "event $nodeId escalationRef '${eventDefinition.escalationRef}' does not match any escalation catalog id",
                    )
                }
            }

            is BpmnTerminateEventDefinition -> {
                Unit
            }
        }
    }
}
