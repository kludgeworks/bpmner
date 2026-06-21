/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation

import dev.groknull.bpmner.bpmn.BpmnBoundaryEvent
import dev.groknull.bpmner.bpmn.BpmnBusinessRuleTask
import dev.groknull.bpmner.bpmn.BpmnCallActivity
import dev.groknull.bpmner.bpmn.BpmnEndEvent
import dev.groknull.bpmner.bpmn.BpmnErrorRef
import dev.groknull.bpmner.bpmn.BpmnEscalationRef
import dev.groknull.bpmner.bpmn.BpmnEventBasedGateway
import dev.groknull.bpmner.bpmn.BpmnExclusiveGateway
import dev.groknull.bpmner.bpmn.BpmnInclusiveGateway
import dev.groknull.bpmner.bpmn.BpmnIntermediateCatchEvent
import dev.groknull.bpmner.bpmn.BpmnIntermediateThrowEvent
import dev.groknull.bpmner.bpmn.BpmnManualTask
import dev.groknull.bpmner.bpmn.BpmnMessageRef
import dev.groknull.bpmner.bpmn.BpmnNode
import dev.groknull.bpmner.bpmn.BpmnNoneEventDefinition
import dev.groknull.bpmner.bpmn.BpmnParallelGateway
import dev.groknull.bpmner.bpmn.BpmnReceiveTask
import dev.groknull.bpmner.bpmn.BpmnScriptTask
import dev.groknull.bpmner.bpmn.BpmnSendTask
import dev.groknull.bpmner.bpmn.BpmnServiceTask
import dev.groknull.bpmner.bpmn.BpmnSignalRef
import dev.groknull.bpmner.bpmn.BpmnStartEvent
import dev.groknull.bpmner.bpmn.BpmnSubProcess
import dev.groknull.bpmner.bpmn.BpmnUserTask
import org.camunda.bpm.model.bpmn.instance.BoundaryEvent
import org.camunda.bpm.model.bpmn.instance.BusinessRuleTask
import org.camunda.bpm.model.bpmn.instance.CallActivity
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

internal fun FlowNode.toBpmnTaskOrNull(normalisedName: String?, parentRef: String?, taskMetadata: TaskMetadata): BpmnNode? {
    val mi = taskMetadata.multiInstance[id]
    val sl = taskMetadata.standardLoop[id]
    val dRef = taskMetadata.decisionRefs[id].orEmpty()
    val mRef = taskMetadata.messageRefs[id].orEmpty()

    return when (this) {
        is UserTask -> BpmnUserTask(id, normalisedName, mi, sl, parentRef)
        is ServiceTask -> BpmnServiceTask(id, normalisedName, mi, sl, parentRef)
        is ScriptTask -> BpmnScriptTask(id, normalisedName, mi, sl, parentRef)
        is BusinessRuleTask -> BpmnBusinessRuleTask(id, normalisedName, dRef, mi, sl, parentRef)
        is SendTask -> BpmnSendTask(id, normalisedName, mRef, mi, sl, parentRef)
        is ReceiveTask -> BpmnReceiveTask(id, normalisedName, mRef, mi, sl, parentRef)
        is ManualTask -> BpmnManualTask(id, normalisedName, mi, sl, parentRef)
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

        is CallActivity -> {
            // A call activity must reference its called process; one with a blank or absent
            // calledElement is surfaced as unrecognized (so the BpmnSubset rule flags it)
            // rather than fabricating a typed node that renders as <callActivity calledElement="">.
            val target = calledElement?.takeIf(String::isNotBlank)
            if (target == null) {
                toUnrecognizedNode(normalisedName, parentRef)
            } else {
                BpmnCallActivity(
                    id = id,
                    name = normalisedName,
                    calledElement = target,
                    parentRef = parentRef,
                )
            }
        }

        // FlowNode subtypes the parser doesn't translate are surfaced as `BpmnUnrecognizedNode`
        // so the `BpmnSubset` rule can flag them. Policy stays in the rule engine.
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
