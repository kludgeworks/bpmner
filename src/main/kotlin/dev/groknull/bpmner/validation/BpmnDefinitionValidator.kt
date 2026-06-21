/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.validation

import dev.groknull.bpmner.bpmn.BpmnBoundaryEvent
import dev.groknull.bpmner.bpmn.BpmnBusinessRuleTask
import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnEndEvent
import dev.groknull.bpmner.bpmn.BpmnErrorEventDefinition
import dev.groknull.bpmner.bpmn.BpmnEscalationEventDefinition
import dev.groknull.bpmner.bpmn.BpmnEventDefinition
import dev.groknull.bpmner.bpmn.BpmnExclusiveGateway
import dev.groknull.bpmner.bpmn.BpmnInclusiveGateway
import dev.groknull.bpmner.bpmn.BpmnIntermediateCatchEvent
import dev.groknull.bpmner.bpmn.BpmnIntermediateThrowEvent
import dev.groknull.bpmner.bpmn.BpmnMessageEventDefinition
import dev.groknull.bpmner.bpmn.BpmnNode
import dev.groknull.bpmner.bpmn.BpmnNodeNamingPolicy
import dev.groknull.bpmner.bpmn.BpmnNoneEventDefinition
import dev.groknull.bpmner.bpmn.BpmnReceiveTask
import dev.groknull.bpmner.bpmn.BpmnSendTask
import dev.groknull.bpmner.bpmn.BpmnSignalEventDefinition
import dev.groknull.bpmner.bpmn.BpmnStartEvent
import dev.groknull.bpmner.bpmn.BpmnSubProcess
import dev.groknull.bpmner.bpmn.BpmnTerminateEventDefinition
import dev.groknull.bpmner.bpmn.BpmnTimerEventDefinition
import dev.groknull.bpmner.bpmn.BpmnUnrecognizedEventDefinition
import dev.groknull.bpmner.bpmn.isTask
import org.jmolecules.ddd.annotation.Service
import org.springframework.stereotype.Component

@Service
@Component
// BpmnDefinitionValidator implements the non-intrinsic (policy/naming/orchestration) checks that
// cannot live on the domain type itself. The model-intrinsic structural checks (duplicate ids,
// edge ref integrity, required START/END) are delegated to BpmnDefinition.validateStructure().
// The function count reflects this deliberate split: intrinsic behaviour on the domain object,
// policy enforcement here. Collapsing these into fewer functions would blur that boundary.
@Suppress("TooManyFunctions")
internal class BpmnDefinitionValidator {
    fun validate(definition: BpmnDefinition): List<String> {
        val errors = mutableListOf<String>()

        // Model-intrinsic structural checks are owned by the domain type itself.
        // BpmnDefinition.validateStructure() covers: duplicate ids, edge ref integrity,
        // and required top-level START/END events.
        errors.addAll((definition as BpmnDefinition).validateStructure())

        validateNames(definition, errors)
        validateSubProcesses(definition, errors)
        validateEventDefinitions(definition, errors)
        validateTaskPayloads(definition, errors)
        validateDefaultFlows(definition, errors)
        validateSwimlanes(definition, errors)

        return errors
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

    // Subprocess containment integrity. Each guard turns a malformed parentRef into a clean error
    // rather than an unhandled IllegalArgumentException from the renderer's container resolution.
    private fun validateSubProcesses(
        definition: BpmnDefinition,
        errors: MutableList<String>,
    ) {
        val subProcessesById = definition.nodes.filterIsInstance<BpmnSubProcess>().associateBy { it.id }
        val nodesById = definition.nodes.associateBy { it.id }

        validateParentRefTargets(definition, nodesById.keys, subProcessesById.keys, errors)
        validateFlowsStayInScope(definition, nodesById, errors)
        validateNoSubProcessCycles(subProcessesById, errors)
        validateSubProcessRequiredEvents(definition, subProcessesById.values, errors)
        validateEventSubProcesses(definition, subProcessesById.values.filter { it.triggeredByEvent }, errors)
    }

    // An event subprocess is triggered by its typed inner start, not by the parent flow: it must
    // carry no connecting sequence flow, and each inner start must be typed (a non-NONE event
    // definition is the trigger). Interrupting vs non-interrupting rides the inner start's
    // isInterrupting flag and needs no separate check.
    private fun validateEventSubProcesses(
        definition: BpmnDefinition,
        eventSubProcesses: Collection<BpmnSubProcess>,
        errors: MutableList<String>,
    ) {
        if (eventSubProcesses.isEmpty()) return
        // Index once rather than re-scanning per event subprocess.
        val connectedNodeIds = definition.sequences.flatMap { listOf(it.sourceRef, it.targetRef) }.toSet()
        val startEventsByParent = definition.nodes.filterIsInstance<BpmnStartEvent>().groupBy { it.parentRef }

        eventSubProcesses.forEach { sp ->
            if (sp.id in connectedNodeIds) {
                errors.add("event subprocess '${sp.id}' must not have an incoming or outgoing sequence flow")
            }
            startEventsByParent[sp.id]?.forEach { start ->
                if (start.eventDefinition is BpmnNoneEventDefinition) {
                    errors.add(
                        "event subprocess '${sp.id}' start event '${start.id}' must be typed " +
                            "(carry a non-NONE event definition)",
                    )
                }
            }
        }
    }

    // Every non-null parentRef must resolve to an existing node, and that node must be a subprocess
    // — otherwise the renderer cannot find a container for the child.
    private fun validateParentRefTargets(
        definition: BpmnDefinition,
        nodeIds: Set<String>,
        subProcessIds: Set<String>,
        errors: MutableList<String>,
    ) {
        fun check(kind: String, ownerId: String, parentRef: String?) {
            if (parentRef == null) return
            if (parentRef !in nodeIds) {
                errors.add("$kind '$ownerId' parentRef '$parentRef' does not match any node id")
            } else if (parentRef !in subProcessIds) {
                errors.add("$kind '$ownerId' parentRef '$parentRef' must reference a subprocess")
            }
        }
        definition.nodes.forEach { check("node", it.id, it.parentRef) }
        definition.sequences.forEach { check("edge", it.id, it.parentRef) }
    }

    // A sequence flow lives wholly in one scope: its parentRef must match both endpoints' parentRef.
    // BPMN forbids a flow crossing a subprocess boundary. Dangling endpoints are reported by
    // BpmnDefinition.validateStructure() (the edge ref integrity check).
    private fun validateFlowsStayInScope(
        definition: BpmnDefinition,
        nodesById: Map<String, BpmnNode>,
        errors: MutableList<String>,
    ) {
        definition.sequences.forEach { edge ->
            // Dangling endpoints are reported by BpmnDefinition.validateStructure(); only check scope when both resolve.
            val source = nodesById[edge.sourceRef] ?: return@forEach
            val target = nodesById[edge.targetRef] ?: return@forEach
            if (source.parentRef != edge.parentRef || target.parentRef != edge.parentRef) {
                errors.add("edge '${edge.id}' must not cross a subprocess boundary")
            }
        }
    }

    // Walk each subprocess's parentRef chain; revisiting an id means the containment forms a cycle,
    // which would otherwise loop forever / corrupt the rendered tree.
    private fun validateNoSubProcessCycles(
        subProcessesById: Map<String, BpmnSubProcess>,
        errors: MutableList<String>,
    ) {
        subProcessesById.keys.forEach { startId ->
            val seen = mutableSetOf(startId)
            var current = subProcessesById[startId]?.parentRef
            while (current != null) {
                if (!seen.add(current)) {
                    errors.add("subprocess '$startId' has a cyclic parentRef chain")
                    return@forEach
                }
                current = subProcessesById[current]?.parentRef
            }
        }
    }

    // Each embedded subprocess is its own flow scope: it must declare a start and an end among the
    // nodes that name it via parentRef — the subprocess analogue of validateRequiredEvents.
    private fun validateSubProcessRequiredEvents(
        definition: BpmnDefinition,
        subProcesses: Collection<BpmnSubProcess>,
        errors: MutableList<String>,
    ) {
        val childrenByParent = definition.nodes.filter { it.parentRef != null }.groupBy { it.parentRef }
        subProcesses.forEach { subProcess ->
            val children = childrenByParent[subProcess.id].orEmpty()
            if (children.none { it is BpmnStartEvent }) {
                errors.add("subprocess '${subProcess.id}' must contain at least one START_EVENT")
            }
            if (children.none { it is BpmnEndEvent }) {
                errors.add("subprocess '${subProcess.id}' must contain at least one END_EVENT")
            }
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

            // Unrecognized event definitions have no fields this structural validator can
            // check; the `BpmnSubset` rule flags them.
            is BpmnUnrecognizedEventDefinition -> {
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

    // Structural / referential integrity for the collaboration data plane. Complements the
    // Pkl convention rules (MessageFlowAcrossPools, LaneLabelsBusinessRolesPerformers, …) — those
    // check naming and cross-pool *semantics*; this checks that the ids actually resolve and that
    // the partition is well-formed, so the renderer never trips over a dangling reference.
    private fun validateSwimlanes(
        definition: BpmnDefinition,
        errors: MutableList<String>,
    ) {
        if (definition.lanes.isEmpty() &&
            definition.participants.isEmpty() &&
            definition.messageFlows.isEmpty()
        ) {
            return
        }

        val nodeIds = definition.nodes.map { it.id.trim() }.toSet()
        val participantIds = definition.participants.map { it.id.trim() }.toSet()

        validateParticipants(definition, errors)
        validateLanes(definition, nodeIds, participantIds, errors)
        validateMessageFlows(definition, nodeIds, participantIds, errors)
    }

    private fun validateParticipants(
        definition: BpmnDefinition,
        errors: MutableList<String>,
    ) {
        definition.participants
            .map { it.id.trim() }
            .groupBy { it }
            .filter { (id, all) -> id.isNotBlank() && all.size > 1 }
            .keys
            .forEach { errors.add("duplicate participant id: $it") }

        // A non-null processRef names the white-box process this pool owns; in a single-process
        // definition that must be the definition's own process.
        definition.participants.forEach { participant ->
            val processRef = participant.processRef?.trim()
            if (!processRef.isNullOrBlank() && processRef != definition.processId.trim()) {
                errors.add(
                    "participant ${participant.id} processRef '$processRef' does not match the process id " +
                        "'${definition.processId}'",
                )
            }
        }
    }

    private fun validateLanes(
        definition: BpmnDefinition,
        nodeIds: Set<String>,
        participantIds: Set<String>,
        errors: MutableList<String>,
    ) {
        definition.lanes
            .map { it.id.trim() }
            .groupBy { it }
            .filter { (id, all) -> id.isNotBlank() && all.size > 1 }
            .keys
            .forEach { errors.add("duplicate lane id: $it") }

        val seenNodeRefs = mutableSetOf<String>()
        definition.lanes.forEach { lane ->
            val participantId = lane.participantId?.trim()
            if (!participantId.isNullOrBlank() && participantId !in participantIds) {
                errors.add("lane ${lane.id} participantId '$participantId' does not match any participant id")
            }
            lane.flowNodeRefs.forEach { rawRef ->
                val ref = rawRef.trim()
                if (ref !in nodeIds) {
                    errors.add("lane ${lane.id} flowNodeRef '$ref' does not match any node id")
                }
                if (!seenNodeRefs.add(ref)) {
                    errors.add("node '$ref' is assigned to more than one lane")
                }
            }
        }
    }

    private fun validateMessageFlows(
        definition: BpmnDefinition,
        nodeIds: Set<String>,
        participantIds: Set<String>,
        errors: MutableList<String>,
    ) {
        // A message-flow endpoint is either a flow-node id (white-box pool) or a participant id
        // (typically a black-box pool). Cross-pool *semantics* are enforced by the
        // MessageFlowAcrossPools Pkl rule; here we only guard against dangling and self references.
        definition.messageFlows
            .map { it.id.trim() }
            .groupBy { it }
            .filter { (id, all) -> id.isNotBlank() && all.size > 1 }
            .keys
            .forEach { errors.add("duplicate message flow id: $it") }

        val endpointIds = nodeIds + participantIds
        definition.messageFlows.forEach { flow ->
            val source = flow.sourceRef.trim()
            val target = flow.targetRef.trim()
            if (source !in endpointIds) {
                errors.add("message flow ${flow.id} sourceRef '$source' matches no node or participant id")
            }
            if (target !in endpointIds) {
                errors.add("message flow ${flow.id} targetRef '$target' matches no node or participant id")
            }
            if (source.isNotBlank() && source == target) {
                errors.add("message flow ${flow.id} must not connect an element to itself")
            }
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
            // BpmnDefinition.validateStructure() surfaces the missing-sourceRef issue too, but
            // this rule still owns the "isDefault is only valid on EXCLUSIVE_GATEWAY or
            // INCLUSIVE_GATEWAY" guarantee and must fire on the orphan case to be complete.
            if (source == null || (source !is BpmnExclusiveGateway && source !is BpmnInclusiveGateway)) {
                defaults.forEach { edge ->
                    errors.add(
                        "edge ${edge.id} isDefault is only valid when sourceRef points to an" +
                            " EXCLUSIVE_GATEWAY or INCLUSIVE_GATEWAY",
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
