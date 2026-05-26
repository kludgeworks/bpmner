/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.validation

import dev.groknull.bpmner.api.BpmnNodeNamingPolicy
import dev.groknull.bpmner.api.isTask
import dev.groknull.bpmner.core.BpmnBoundaryEvent
import dev.groknull.bpmner.core.BpmnBusinessRuleTask
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnErrorEventDefinition
import dev.groknull.bpmner.core.BpmnEscalationEventDefinition
import dev.groknull.bpmner.core.BpmnEventDefinition
import dev.groknull.bpmner.core.BpmnExclusiveGateway
import dev.groknull.bpmner.core.BpmnIntermediateCatchEvent
import dev.groknull.bpmner.core.BpmnIntermediateThrowEvent
import dev.groknull.bpmner.core.BpmnMessageEventDefinition
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnNoneEventDefinition
import dev.groknull.bpmner.core.BpmnReceiveTask
import dev.groknull.bpmner.core.BpmnSendTask
import dev.groknull.bpmner.core.BpmnSignalEventDefinition
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnTerminateEventDefinition
import dev.groknull.bpmner.core.BpmnTimerEventDefinition
import org.jmolecules.ddd.annotation.Service
import org.springframework.stereotype.Component

@Service
@Component
@Suppress("TooManyFunctions")
internal class BpmnDefinitionValidator {
    fun validate(definition: BpmnDefinition): List<String> {
        val errors = mutableListOf<String>()

        validateDuplicateIds(definition, errors)
        validateNames(definition, errors)
        validateEdges(definition, errors)
        validateRequiredEvents(definition, errors)
        validateEventDefinitions(definition, errors)
        validateTaskPayloads(definition, errors)
        validateDefaultFlows(definition, errors)

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

    private data class EventValidationContext(
        val nodesById: Map<String, BpmnNode>,
        val messageIds: Set<String>,
        val signalIds: Set<String>,
        val errorIds: Set<String>,
        val escalationIds: Set<String>,
    )

    private fun validateEventDefinitions(
        definition: BpmnDefinition,
        errors: MutableList<String>,
    ) {
        val context = EventValidationContext(
            nodesById = definition.nodes.associateBy { it.id },
            messageIds = definition.messages.map { it.id }.toSet(),
            signalIds = definition.signals.map { it.id }.toSet(),
            errorIds = definition.errors.map { it.id }.toSet(),
            escalationIds = definition.escalations.map { it.id }.toSet(),
        )

        definition.nodes.forEach { node ->
            when (node) {
                is BpmnStartEvent -> validateEventDefinition(node.id, node.eventDefinition, context, errors)
                is BpmnEndEvent -> validateEventDefinition(node.id, node.eventDefinition, context, errors)
                is BpmnIntermediateCatchEvent -> {
                    validateRequiredEventDefinition(
                        "intermediate catch event",
                        node.id,
                        node.eventDefinition,
                        errors,
                    )
                    validateEventDefinition(node.id, node.eventDefinition, context, errors)
                }
                is BpmnIntermediateThrowEvent -> {
                    validateRequiredEventDefinition(
                        "intermediate throw event",
                        node.id,
                        node.eventDefinition,
                        errors,
                    )
                    validateEventDefinition(node.id, node.eventDefinition, context, errors)
                }
                is BpmnBoundaryEvent -> validateBoundaryEvent(node, context, errors)
                else -> Unit
            }
        }
    }

    private fun validateRequiredEventDefinition(
        label: String,
        nodeId: String,
        eventDefinition: BpmnEventDefinition,
        errors: MutableList<String>,
    ) {
        if (eventDefinition is BpmnNoneEventDefinition) {
            errors.add("$label $nodeId must declare an event definition")
        }
    }

    private fun validateBoundaryEvent(
        node: BpmnBoundaryEvent,
        context: EventValidationContext,
        errors: MutableList<String>,
    ) {
        validateRequiredEventDefinition("boundary event", node.id, node.eventDefinition, errors)
        if (node.attachedToRef.isBlank()) {
            errors.add("boundary event ${node.id} is missing the required attachedToRef attribute")
        } else {
            val attachedTo = context.nodesById[node.attachedToRef]
            if (attachedTo == null) {
                errors.add(
                    "boundary event ${node.id} attachedToRef '${node.attachedToRef}' " +
                        "does not match any node id",
                )
            } else if (!attachedTo.isTask()) {
                errors.add(
                    "boundary event ${node.id} attachedToRef '${node.attachedToRef}' " +
                        "must reference an attachable activity",
                )
            }
        }
        validateEventDefinition(node.id, node.eventDefinition, context, errors)
    }

    @Suppress("CyclomaticComplexMethod")
    private fun validateEventDefinition(
        nodeId: String,
        eventDefinition: BpmnEventDefinition,
        context: EventValidationContext,
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
                if (eventDefinition.messageRef.isBlank()) {
                    errors.add(
                        "event $nodeId messageEventDefinition is missing the required messageRef attribute",
                    )
                } else if (eventDefinition.messageRef !in context.messageIds) {
                    errors.add(
                        "event $nodeId messageRef '${eventDefinition.messageRef}' " +
                            "does not match any message catalog id",
                    )
                }
            }

            is BpmnSignalEventDefinition -> {
                if (eventDefinition.signalRef.isBlank()) {
                    errors.add(
                        "event $nodeId signalEventDefinition is missing the required signalRef attribute",
                    )
                } else if (eventDefinition.signalRef !in context.signalIds) {
                    errors.add("event $nodeId signalRef '${eventDefinition.signalRef}' does not match any signal catalog id")
                }
            }

            is BpmnErrorEventDefinition -> {
                if (eventDefinition.errorRef.isBlank()) {
                    errors.add(
                        "event $nodeId errorEventDefinition is missing the required errorRef attribute",
                    )
                } else if (eventDefinition.errorRef !in context.errorIds) {
                    errors.add("event $nodeId errorRef '${eventDefinition.errorRef}' does not match any error catalog id")
                }
            }

            is BpmnEscalationEventDefinition -> {
                if (eventDefinition.escalationRef.isBlank()) {
                    errors.add(
                        "event $nodeId escalationEventDefinition is missing the required escalationRef attribute",
                    )
                } else if (eventDefinition.escalationRef !in context.escalationIds) {
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

    private fun validateTaskPayloads(
        definition: BpmnDefinition,
        errors: MutableList<String>,
    ) {
        val messageIds = definition.messages.map { it.id }.toSet()
        definition.nodes.forEach { node ->
            when (node) {
                is BpmnSendTask -> {
                    validateMessageRef(node.id, "sendTask", node.messageRef, messageIds, errors)
                }

                is BpmnReceiveTask -> {
                    validateMessageRef(node.id, "receiveTask", node.messageRef, messageIds, errors)
                }

                is BpmnBusinessRuleTask -> {
                    if (node.decisionRef.isBlank()) {
                        errors.add(
                            "businessRuleTask ${node.id} is missing the required decisionRef attribute",
                        )
                    }
                    // No decisionRef catalogue exists today (cf. issue #196 cross-cutting), so we
                    // only require non-blank. When a typed decision catalogue lands, add a
                    // catalogue-resolution check here matching the messageRef pattern.
                }

                else -> {
                    Unit
                }
            }
        }
    }

    private fun validateMessageRef(
        nodeId: String,
        elementName: String,
        messageRef: String,
        messageIds: Set<String>,
        errors: MutableList<String>,
    ) {
        if (messageRef.isBlank()) {
            errors.add(
                "$elementName $nodeId is missing the required messageRef attribute",
            )
        } else if (messageRef !in messageIds) {
            errors.add(
                "$elementName $nodeId messageRef '$messageRef' " +
                    "does not match any message catalog id",
            )
        }
    }

    private fun validateDefaultFlows(
        definition: BpmnDefinition,
        errors: MutableList<String>,
    ) {
        val nodesById = definition.nodes.associateBy { it.id }
        val defaultsBySource =
            definition.sequences
                .filter { it.isDefault }
                .groupBy { it.sourceRef }
        defaultsBySource.forEach { (sourceId, defaults) ->
            val source = nodesById[sourceId]
            // An orphan isDefault edge (sourceRef points to no node) is also invalid here.
            // The separate validateEdges pass surfaces the missing-sourceRef issue too, but
            // this rule still owns the "isDefault is only valid on EXCLUSIVE_GATEWAY" guarantee
            // and must fire on the orphan case to be complete.
            if (source == null || source !is BpmnExclusiveGateway) {
                defaults.forEach { edge ->
                    errors.add(
                        "edge ${edge.id} isDefault is only valid when sourceRef points to an EXCLUSIVE_GATEWAY",
                    )
                }
            }
            if (defaults.size > 1) {
                val ids = defaults.joinToString(", ") { it.id }
                errors.add(
                    "node $sourceId has ${defaults.size} default flows ($ids); at most one is allowed",
                )
            }
        }
    }
}
