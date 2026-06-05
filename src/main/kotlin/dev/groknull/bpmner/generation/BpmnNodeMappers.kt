/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation

import dev.groknull.bpmner.core.BpmnBoundaryEvent
import dev.groknull.bpmner.core.BpmnBusinessRuleTask
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnErrorRef
import dev.groknull.bpmner.core.BpmnEscalationRef
import dev.groknull.bpmner.core.BpmnEventBasedGateway
import dev.groknull.bpmner.core.BpmnExclusiveGateway
import dev.groknull.bpmner.core.BpmnInclusiveGateway
import dev.groknull.bpmner.core.BpmnIntermediateCatchEvent
import dev.groknull.bpmner.core.BpmnIntermediateThrowEvent
import dev.groknull.bpmner.core.BpmnManualTask
import dev.groknull.bpmner.core.BpmnMessageRef
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnNoneEventDefinition
import dev.groknull.bpmner.core.BpmnParallelGateway
import dev.groknull.bpmner.core.BpmnReceiveTask
import dev.groknull.bpmner.core.BpmnScriptTask
import dev.groknull.bpmner.core.BpmnSendTask
import dev.groknull.bpmner.core.BpmnServiceTask
import dev.groknull.bpmner.core.BpmnSignalRef
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnSubProcess
import dev.groknull.bpmner.core.BpmnUserTask
import org.camunda.bpm.model.bpmn.instance.BoundaryEvent
import org.camunda.bpm.model.bpmn.instance.BusinessRuleTask
import org.camunda.bpm.model.bpmn.instance.EndEvent
import org.camunda.bpm.model.bpmn.instance.EventBasedGateway
import org.camunda.bpm.model.bpmn.instance.ExclusiveGateway
import org.camunda.bpm.model.bpmn.instance.FlowNode
import org.camunda.bpm.model.bpmn.instance.InclusiveGateway
import org.camunda.bpm.model.bpmn.instance.IntermediateCatchEvent
import org.camunda.bpm.model.bpmn.instance.IntermediateThrowEvent
import org.camunda.bpm.model.bpmn.instance.ManualTask
import org.camunda.bpm.model.bpmn.instance.ParallelGateway
import org.camunda.bpm.model.bpmn.instance.ReceiveTask
import org.camunda.bpm.model.bpmn.instance.ScriptTask
import org.camunda.bpm.model.bpmn.instance.SendTask
import org.camunda.bpm.model.bpmn.instance.ServiceTask
import org.camunda.bpm.model.bpmn.instance.StartEvent
import org.camunda.bpm.model.bpmn.instance.SubProcess
import org.camunda.bpm.model.bpmn.instance.Transaction
import org.camunda.bpm.model.bpmn.instance.UserTask
import org.w3c.dom.Document

@Suppress("LongMethod")
internal fun FlowNode.toBpmnTaskOrNull(normalisedName: String?, parentRef: String?, taskMetadata: TaskMetadata): BpmnNode? {
    return when (this) {
        is UserTask -> {
            BpmnUserTask(
                id = id,
                name = normalisedName,
                multiInstance = taskMetadata.multiInstance[id],
                standardLoop = taskMetadata.standardLoop[id],
                parentRef = parentRef,
            )
        }

        is ServiceTask -> {
            BpmnServiceTask(
                id = id,
                name = normalisedName,
                multiInstance = taskMetadata.multiInstance[id],
                standardLoop = taskMetadata.standardLoop[id],
                parentRef = parentRef,
            )
        }

        is ScriptTask -> {
            BpmnScriptTask(
                id = id,
                name = normalisedName,
                multiInstance = taskMetadata.multiInstance[id],
                standardLoop = taskMetadata.standardLoop[id],
                parentRef = parentRef,
            )
        }

        is BusinessRuleTask -> {
            BpmnBusinessRuleTask(
                id = id,
                name = normalisedName,
                decisionRef = taskMetadata.decisionRefs[id].orEmpty(),
                multiInstance = taskMetadata.multiInstance[id],
                standardLoop = taskMetadata.standardLoop[id],
                parentRef = parentRef,
            )
        }

        is SendTask -> {
            BpmnSendTask(
                id = id,
                name = normalisedName,
                messageRef = taskMetadata.messageRefs[id].orEmpty(),
                multiInstance = taskMetadata.multiInstance[id],
                standardLoop = taskMetadata.standardLoop[id],
                parentRef = parentRef,
            )
        }

        is ReceiveTask -> {
            BpmnReceiveTask(
                id = id,
                name = normalisedName,
                messageRef = taskMetadata.messageRefs[id].orEmpty(),
                multiInstance = taskMetadata.multiInstance[id],
                standardLoop = taskMetadata.standardLoop[id],
                parentRef = parentRef,
            )
        }

        is ManualTask -> {
            BpmnManualTask(
                id = id,
                name = normalisedName,
                multiInstance = taskMetadata.multiInstance[id],
                standardLoop = taskMetadata.standardLoop[id],
                parentRef = parentRef,
            )
        }

        else -> null
    }
}

internal fun FlowNode.toBpmnEventOrNull(normalisedName: String?, parentRef: String?, eventMetadata: EventMetadata): BpmnNode? {
    return when (this) {
        is StartEvent -> {
            BpmnStartEvent(
                id = id,
                name = normalisedName,
                eventDefinition = eventMetadata.eventDefinitions[id] ?: BpmnNoneEventDefinition,
                isInterrupting = eventMetadata.isInterrupting[id] ?: true,
                parentRef = parentRef,
            )
        }

        is IntermediateCatchEvent -> {
            BpmnIntermediateCatchEvent(
                id = id,
                name = normalisedName,
                eventDefinition = eventMetadata.eventDefinitions[id] ?: BpmnNoneEventDefinition,
                parentRef = parentRef,
            )
        }

        is IntermediateThrowEvent -> {
            BpmnIntermediateThrowEvent(
                id = id,
                name = normalisedName,
                eventDefinition = eventMetadata.eventDefinitions[id] ?: BpmnNoneEventDefinition,
                parentRef = parentRef,
            )
        }

        is BoundaryEvent -> {
            BpmnBoundaryEvent(
                id = id,
                name = normalisedName,
                attachedToRef = eventMetadata.attachedToRefs[id].orEmpty(),
                cancelActivity = eventMetadata.cancelActivity[id] ?: true,
                eventDefinition = eventMetadata.eventDefinitions[id] ?: BpmnNoneEventDefinition,
                parentRef = parentRef,
            )
        }

        is EndEvent -> {
            BpmnEndEvent(
                id = id,
                name = normalisedName,
                eventDefinition = eventMetadata.eventDefinitions[id] ?: BpmnNoneEventDefinition,
                parentRef = parentRef,
            )
        }

        else -> null
    }
}

internal fun FlowNode.toBpmnGatewayOrNull(normalisedName: String?, parentRef: String?): BpmnNode? {
    return when (this) {
        is ExclusiveGateway -> {
            BpmnExclusiveGateway(id = id, name = normalisedName, parentRef = parentRef)
        }

        is InclusiveGateway -> {
            BpmnInclusiveGateway(id = id, name = normalisedName, parentRef = parentRef)
        }

        is ParallelGateway -> {
            BpmnParallelGateway(id = id, name = normalisedName, parentRef = parentRef)
        }

        is EventBasedGateway -> {
            BpmnEventBasedGateway(id = id, name = normalisedName, parentRef = parentRef)
        }

        else -> null
    }
}

internal fun FlowNode.toBpmnSubProcessOrUnrecognized(normalisedName: String?, parentRef: String?): BpmnNode {
    return when (this) {
        // Transaction is a SubProcess subtype but carries distinct semantics the model
        // doesn't represent, so it stays on the unrecognized path (parser-as-structure) —
        // only a plain embedded subprocess becomes BpmnSubProcess.
        is SubProcess -> {
            if (this is Transaction) {
                toUnrecognizedNode(normalisedName, parentRef)
            } else {
                BpmnSubProcess(
                    id = id,
                    name = normalisedName,
                    triggeredByEvent = triggeredByEvent(),
                    parentRef = parentRef,
                )
            }
        }

        // FlowNode subtypes the parser doesn't translate (CallActivity, etc.) are surfaced
        // as `BpmnUnrecognizedNode` so the `BpmnSubset` rule can flag them. Policy stays in
        // the rule engine.
        else -> toUnrecognizedNode(normalisedName, parentRef)
    }
}

internal fun Document.parseMessages(): List<BpmnMessageRef> {
    return bpmnElements("message")
        .map { BpmnMessageRef(id = it.getAttribute("id"), name = it.getAttribute("name")) }
        .filter { it.id.isNotBlank() && it.name.isNotBlank() }
        .toList()
}

internal fun Document.parseSignals(): List<BpmnSignalRef> {
    return bpmnElements("signal")
        .map { BpmnSignalRef(id = it.getAttribute("id"), name = it.getAttribute("name")) }
        .filter { it.id.isNotBlank() && it.name.isNotBlank() }
        .toList()
}

internal fun Document.parseErrors(): List<BpmnErrorRef> {
    return bpmnElements("error")
        .map {
            BpmnErrorRef(
                id = it.getAttribute("id"),
                code = it.getAttribute("errorCode"),
                name = it.getAttribute("name").takeIf { name -> name.isNotBlank() },
            )
        }.filter { it.id.isNotBlank() && it.code.isNotBlank() }
        .toList()
}

internal fun Document.parseEscalations(): List<BpmnEscalationRef> {
    return bpmnElements("escalation")
        .map {
            BpmnEscalationRef(
                id = it.getAttribute("id"),
                code = it.getAttribute("escalationCode"),
                name = it.getAttribute("name").takeIf { name -> name.isNotBlank() },
            )
        }.filter { it.id.isNotBlank() && it.code.isNotBlank() }
        .toList()
}
