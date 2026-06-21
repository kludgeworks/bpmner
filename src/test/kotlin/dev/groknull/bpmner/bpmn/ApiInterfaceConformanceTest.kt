/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.bpmn

import dev.groknull.bpmner.bpmn.internal.model.BpmnBoundaryEvent
import dev.groknull.bpmner.bpmn.internal.model.BpmnBusinessRuleTask
import dev.groknull.bpmner.bpmn.internal.model.BpmnDefinition
import dev.groknull.bpmner.bpmn.internal.model.BpmnEdge
import dev.groknull.bpmner.bpmn.internal.model.BpmnEndEvent
import dev.groknull.bpmner.bpmn.internal.model.BpmnErrorEventDefinition
import dev.groknull.bpmner.bpmn.internal.model.BpmnErrorRef
import dev.groknull.bpmner.bpmn.internal.model.BpmnEscalationEventDefinition
import dev.groknull.bpmner.bpmn.internal.model.BpmnEscalationRef
import dev.groknull.bpmner.bpmn.internal.model.BpmnExclusiveGateway
import dev.groknull.bpmner.bpmn.internal.model.BpmnIntermediateCatchEvent
import dev.groknull.bpmner.bpmn.internal.model.BpmnIntermediateThrowEvent
import dev.groknull.bpmner.bpmn.internal.model.BpmnManualTask
import dev.groknull.bpmner.bpmn.internal.model.BpmnMessageEventDefinition
import dev.groknull.bpmner.bpmn.internal.model.BpmnMessageRef
import dev.groknull.bpmner.bpmn.internal.model.BpmnNoneEventDefinition
import dev.groknull.bpmner.bpmn.internal.model.BpmnParallelGateway
import dev.groknull.bpmner.bpmn.internal.model.BpmnReceiveTask
import dev.groknull.bpmner.bpmn.internal.model.BpmnRequest
import dev.groknull.bpmner.bpmn.internal.model.BpmnScriptTask
import dev.groknull.bpmner.bpmn.internal.model.BpmnSendTask
import dev.groknull.bpmner.bpmn.internal.model.BpmnServiceTask
import dev.groknull.bpmner.bpmn.internal.model.BpmnSignalEventDefinition
import dev.groknull.bpmner.bpmn.internal.model.BpmnSignalRef
import dev.groknull.bpmner.bpmn.internal.model.BpmnStartEvent
import dev.groknull.bpmner.bpmn.internal.model.BpmnTerminateEventDefinition
import dev.groknull.bpmner.bpmn.internal.model.BpmnTimerEventDefinition
import dev.groknull.bpmner.bpmn.internal.model.BpmnUserTask
import dev.groknull.bpmner.readiness.ClarificationExchange
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import dev.groknull.bpmner.bpmn.BpmnBoundaryEvent as ApiBpmnBoundaryEvent
import dev.groknull.bpmner.bpmn.BpmnBusinessRuleTask as ApiBpmnBusinessRuleTask
import dev.groknull.bpmner.bpmn.BpmnDefinition as ApiBpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnEdge as ApiBpmnEdge
import dev.groknull.bpmner.bpmn.BpmnEndEvent as ApiBpmnEndEvent
import dev.groknull.bpmner.bpmn.BpmnErrorEventDefinition as ApiBpmnErrorEventDefinition
import dev.groknull.bpmner.bpmn.BpmnErrorRef as ApiBpmnErrorRef
import dev.groknull.bpmner.bpmn.BpmnEscalationEventDefinition as ApiBpmnEscalationEventDefinition
import dev.groknull.bpmner.bpmn.BpmnEscalationRef as ApiBpmnEscalationRef
import dev.groknull.bpmner.bpmn.BpmnEvent as ApiBpmnEvent
import dev.groknull.bpmner.bpmn.BpmnExclusiveGateway as ApiBpmnExclusiveGateway
import dev.groknull.bpmner.bpmn.BpmnGateway as ApiBpmnGateway
import dev.groknull.bpmner.bpmn.BpmnIntermediateCatchEvent as ApiBpmnIntermediateCatchEvent
import dev.groknull.bpmner.bpmn.BpmnIntermediateThrowEvent as ApiBpmnIntermediateThrowEvent
import dev.groknull.bpmner.bpmn.BpmnManualTask as ApiBpmnManualTask
import dev.groknull.bpmner.bpmn.BpmnMessageEventDefinition as ApiBpmnMessageEventDefinition
import dev.groknull.bpmner.bpmn.BpmnMessageRef as ApiBpmnMessageRef
import dev.groknull.bpmner.bpmn.BpmnNode as ApiBpmnNode
import dev.groknull.bpmner.bpmn.BpmnNoneEventDefinition as ApiBpmnNoneEventDefinition
import dev.groknull.bpmner.bpmn.BpmnParallelGateway as ApiBpmnParallelGateway
import dev.groknull.bpmner.bpmn.BpmnReceiveTask as ApiBpmnReceiveTask
import dev.groknull.bpmner.bpmn.BpmnRequest as ApiBpmnRequest
import dev.groknull.bpmner.bpmn.BpmnScriptTask as ApiBpmnScriptTask
import dev.groknull.bpmner.bpmn.BpmnSendTask as ApiBpmnSendTask
import dev.groknull.bpmner.bpmn.BpmnServiceTask as ApiBpmnServiceTask
import dev.groknull.bpmner.bpmn.BpmnSignalEventDefinition as ApiBpmnSignalEventDefinition
import dev.groknull.bpmner.bpmn.BpmnSignalRef as ApiBpmnSignalRef
import dev.groknull.bpmner.bpmn.BpmnStartEvent as ApiBpmnStartEvent
import dev.groknull.bpmner.bpmn.BpmnTask as ApiBpmnTask
import dev.groknull.bpmner.bpmn.BpmnTerminateEventDefinition as ApiBpmnTerminateEventDefinition
import dev.groknull.bpmner.bpmn.BpmnTimerEventDefinition as ApiBpmnTimerEventDefinition
import dev.groknull.bpmner.bpmn.BpmnUserTask as ApiBpmnUserTask
import dev.groknull.bpmner.bpmn.ClarificationExchange as ApiClarificationExchange
import dev.groknull.bpmner.bpmn.internal.model.BpmnAssociation as ModelBpmnAssociation
import dev.groknull.bpmner.bpmn.internal.model.BpmnEventDefinition as ModelBpmnEventDefinition
import dev.groknull.bpmner.bpmn.internal.model.BpmnTextAnnotation as ModelBpmnTextAnnotation
import dev.groknull.bpmner.bpmn.internal.model.MultiInstanceLoopCharacteristics as ModelMultiInstanceLoopCharacteristics

/**
 * Asserts that every concrete `core` data class for the BPMN domain implements its specific
 * `bpmn` interface — both the leaf interface and (transitively) the appropriate grouping
 * marker (`BpmnEvent` / `BpmnTask` / `BpmnGateway`). Failing arm = a core class that
 * declares a Jackson-annotated shape but forgot to implement the bpmn contract the rule
 * engine programs against.
 */
class ApiInterfaceConformanceTest {
    @Test
    fun `every BpmnNode subtype implements its specific api leaf and marker`() {
        val startEvent: ApiBpmnNode = BpmnStartEvent(id = "start")
        assertTrue(startEvent is ApiBpmnStartEvent)
        assertTrue(startEvent is ApiBpmnEvent)

        val endEvent: ApiBpmnNode = BpmnEndEvent(id = "end")
        assertTrue(endEvent is ApiBpmnEndEvent)
        assertTrue(endEvent is ApiBpmnEvent)

        val userTask: ApiBpmnNode = BpmnUserTask(id = "ut")
        assertTrue(userTask is ApiBpmnUserTask)
        assertTrue(userTask is ApiBpmnTask)

        val serviceTask: ApiBpmnNode = BpmnServiceTask(id = "st")
        assertTrue(serviceTask is ApiBpmnServiceTask)
        assertTrue(serviceTask is ApiBpmnTask)

        val scriptTask: ApiBpmnNode = BpmnScriptTask(id = "sct")
        assertTrue(scriptTask is ApiBpmnScriptTask)
        assertTrue(scriptTask is ApiBpmnTask)

        val brTask: ApiBpmnNode = BpmnBusinessRuleTask(id = "br", decisionRef = "d1")
        assertTrue(brTask is ApiBpmnBusinessRuleTask)
        assertTrue(brTask is ApiBpmnTask)

        val sendTask: ApiBpmnNode = BpmnSendTask(id = "send", messageRef = "m1")
        assertTrue(sendTask is ApiBpmnSendTask)
        assertTrue(sendTask is ApiBpmnTask)

        val recvTask: ApiBpmnNode = BpmnReceiveTask(id = "recv", messageRef = "m1")
        assertTrue(recvTask is ApiBpmnReceiveTask)
        assertTrue(recvTask is ApiBpmnTask)

        val manualTask: ApiBpmnNode = BpmnManualTask(id = "manual")
        assertTrue(manualTask is ApiBpmnManualTask)
        assertTrue(manualTask is ApiBpmnTask)

        val exclusiveGw: ApiBpmnNode = BpmnExclusiveGateway(id = "xgw")
        assertTrue(exclusiveGw is ApiBpmnExclusiveGateway)
        assertTrue(exclusiveGw is ApiBpmnGateway)

        val parallelGw: ApiBpmnNode = BpmnParallelGateway(id = "pgw")
        assertTrue(parallelGw is ApiBpmnParallelGateway)
        assertTrue(parallelGw is ApiBpmnGateway)

        val icEvent: ApiBpmnNode = BpmnIntermediateCatchEvent(id = "ic", eventDefinition = BpmnNoneEventDefinition)
        assertTrue(icEvent is ApiBpmnIntermediateCatchEvent)
        assertTrue(icEvent is ApiBpmnEvent)

        val itEvent: ApiBpmnNode = BpmnIntermediateThrowEvent(id = "it", eventDefinition = BpmnNoneEventDefinition)
        assertTrue(itEvent is ApiBpmnIntermediateThrowEvent)
        assertTrue(itEvent is ApiBpmnEvent)

        val boundaryEvent: ApiBpmnNode =
            BpmnBoundaryEvent(id = "be", attachedToRef = "a", eventDefinition = BpmnNoneEventDefinition)
        assertTrue(boundaryEvent is ApiBpmnBoundaryEvent)
        assertTrue(boundaryEvent is ApiBpmnEvent)
    }

    @Test
    fun `every BpmnEventDefinition subtype implements its api counterpart`() {
        val none: ModelBpmnEventDefinition = BpmnNoneEventDefinition
        assertTrue(none is ApiBpmnNoneEventDefinition)

        val timer: ModelBpmnEventDefinition =
            BpmnTimerEventDefinition(timerKind = BpmnTimerKind.DURATION, expression = "PT5M")
        assertTrue(timer is ApiBpmnTimerEventDefinition)

        val message: ModelBpmnEventDefinition = BpmnMessageEventDefinition(messageRef = "m")
        assertTrue(message is ApiBpmnMessageEventDefinition)

        val signal: ModelBpmnEventDefinition = BpmnSignalEventDefinition(signalRef = "s")
        assertTrue(signal is ApiBpmnSignalEventDefinition)

        val error: ModelBpmnEventDefinition = BpmnErrorEventDefinition(errorRef = "e")
        assertTrue(error is ApiBpmnErrorEventDefinition)

        val escalation: ModelBpmnEventDefinition = BpmnEscalationEventDefinition(escalationRef = "esc")
        assertTrue(escalation is ApiBpmnEscalationEventDefinition)

        val terminate: ModelBpmnEventDefinition = BpmnTerminateEventDefinition
        assertTrue(terminate is ApiBpmnTerminateEventDefinition)
    }

    @Test
    fun `BpmnDefinition BpmnEdge and catalog data classes implement their api counterparts`() {
        val definition =
            BpmnDefinition(
                processId = "p1",
                processName = "Process 1",
                nodes = listOf(BpmnStartEvent(id = "s"), BpmnEndEvent(id = "e")),
                sequences = listOf(BpmnEdge(id = "f1", sourceRef = "s", targetRef = "e")),
            )
        assertTrue(definition as Any is ApiBpmnDefinition)
        assertTrue(definition.sequences.first() as Any is ApiBpmnEdge)

        assertTrue(BpmnMessageRef(id = "m1", name = "M") as Any is ApiBpmnMessageRef)
        assertTrue(BpmnSignalRef(id = "s1", name = "S") as Any is ApiBpmnSignalRef)
        assertTrue(BpmnErrorRef(id = "er1", code = "ERR") as Any is ApiBpmnErrorRef)
        assertTrue(BpmnEscalationRef(id = "esc1", code = "ESC") as Any is ApiBpmnEscalationRef)
    }

    @Test
    fun `multi-instance, text annotation, and association types implement their api counterparts`() {
        val coreMi = ModelMultiInstanceLoopCharacteristics(
            mode = MultiInstanceMode.PARALLEL,
            collectionDescription = "each reviewer",
        )
        assertTrue(coreMi as Any is MultiInstanceLoopCharacteristics)

        val annotation = ModelBpmnTextAnnotation(id = "ta", text = "note")
        assertTrue(annotation as Any is BpmnTextAnnotation)

        val association = ModelBpmnAssociation(id = "as", sourceRef = "a", targetRef = "ta")
        assertTrue(association as Any is BpmnAssociation)

        // The cross-cutting `multiInstance` field is reachable through the bpmn BpmnTask marker.
        val task: ApiBpmnTask = BpmnUserTask(id = "u", multiInstance = coreMi)
        assertTrue(task.multiInstance is MultiInstanceLoopCharacteristics)
    }

    @Test
    fun `BpmnRequest and ClarificationExchange implement their api counterparts`() {
        val request = BpmnRequest(processDescription = "ship the order")
        assertTrue(request as Any is ApiBpmnRequest)

        val exchange = ClarificationExchange(questionId = "q1", questionText = "?", answerText = "A")
        assertTrue(exchange as Any is ApiClarificationExchange)
    }
}
