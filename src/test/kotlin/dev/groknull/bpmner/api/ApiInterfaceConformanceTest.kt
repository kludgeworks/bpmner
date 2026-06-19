/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.api

import dev.groknull.bpmner.domain.BpmnBoundaryEvent
import dev.groknull.bpmner.domain.BpmnBusinessRuleTask
import dev.groknull.bpmner.domain.BpmnDefinition
import dev.groknull.bpmner.domain.BpmnEdge
import dev.groknull.bpmner.domain.BpmnEndEvent
import dev.groknull.bpmner.domain.BpmnErrorEventDefinition
import dev.groknull.bpmner.domain.BpmnErrorRef
import dev.groknull.bpmner.domain.BpmnEscalationEventDefinition
import dev.groknull.bpmner.domain.BpmnEscalationRef
import dev.groknull.bpmner.domain.BpmnExclusiveGateway
import dev.groknull.bpmner.domain.BpmnIntermediateCatchEvent
import dev.groknull.bpmner.domain.BpmnIntermediateThrowEvent
import dev.groknull.bpmner.domain.BpmnManualTask
import dev.groknull.bpmner.domain.BpmnMessageEventDefinition
import dev.groknull.bpmner.domain.BpmnMessageRef
import dev.groknull.bpmner.domain.BpmnNoneEventDefinition
import dev.groknull.bpmner.domain.BpmnParallelGateway
import dev.groknull.bpmner.domain.BpmnReceiveTask
import dev.groknull.bpmner.domain.BpmnRequest
import dev.groknull.bpmner.domain.BpmnScriptTask
import dev.groknull.bpmner.domain.BpmnSendTask
import dev.groknull.bpmner.domain.BpmnServiceTask
import dev.groknull.bpmner.domain.BpmnSignalEventDefinition
import dev.groknull.bpmner.domain.BpmnSignalRef
import dev.groknull.bpmner.domain.BpmnStartEvent
import dev.groknull.bpmner.domain.BpmnTerminateEventDefinition
import dev.groknull.bpmner.domain.BpmnTimerEventDefinition
import dev.groknull.bpmner.domain.BpmnUserTask
import dev.groknull.bpmner.readiness.ClarificationExchange
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import dev.groknull.bpmner.api.BpmnBoundaryEvent as ApiBpmnBoundaryEvent
import dev.groknull.bpmner.api.BpmnBusinessRuleTask as ApiBpmnBusinessRuleTask
import dev.groknull.bpmner.api.BpmnDefinition as ApiBpmnDefinition
import dev.groknull.bpmner.api.BpmnEdge as ApiBpmnEdge
import dev.groknull.bpmner.api.BpmnEndEvent as ApiBpmnEndEvent
import dev.groknull.bpmner.api.BpmnErrorEventDefinition as ApiBpmnErrorEventDefinition
import dev.groknull.bpmner.api.BpmnErrorRef as ApiBpmnErrorRef
import dev.groknull.bpmner.api.BpmnEscalationEventDefinition as ApiBpmnEscalationEventDefinition
import dev.groknull.bpmner.api.BpmnEscalationRef as ApiBpmnEscalationRef
import dev.groknull.bpmner.api.BpmnEvent as ApiBpmnEvent
import dev.groknull.bpmner.api.BpmnExclusiveGateway as ApiBpmnExclusiveGateway
import dev.groknull.bpmner.api.BpmnGateway as ApiBpmnGateway
import dev.groknull.bpmner.api.BpmnIntermediateCatchEvent as ApiBpmnIntermediateCatchEvent
import dev.groknull.bpmner.api.BpmnIntermediateThrowEvent as ApiBpmnIntermediateThrowEvent
import dev.groknull.bpmner.api.BpmnManualTask as ApiBpmnManualTask
import dev.groknull.bpmner.api.BpmnMessageEventDefinition as ApiBpmnMessageEventDefinition
import dev.groknull.bpmner.api.BpmnMessageRef as ApiBpmnMessageRef
import dev.groknull.bpmner.api.BpmnNode as ApiBpmnNode
import dev.groknull.bpmner.api.BpmnNoneEventDefinition as ApiBpmnNoneEventDefinition
import dev.groknull.bpmner.api.BpmnParallelGateway as ApiBpmnParallelGateway
import dev.groknull.bpmner.api.BpmnReceiveTask as ApiBpmnReceiveTask
import dev.groknull.bpmner.api.BpmnRequest as ApiBpmnRequest
import dev.groknull.bpmner.api.BpmnScriptTask as ApiBpmnScriptTask
import dev.groknull.bpmner.api.BpmnSendTask as ApiBpmnSendTask
import dev.groknull.bpmner.api.BpmnServiceTask as ApiBpmnServiceTask
import dev.groknull.bpmner.api.BpmnSignalEventDefinition as ApiBpmnSignalEventDefinition
import dev.groknull.bpmner.api.BpmnSignalRef as ApiBpmnSignalRef
import dev.groknull.bpmner.api.BpmnStartEvent as ApiBpmnStartEvent
import dev.groknull.bpmner.api.BpmnTask as ApiBpmnTask
import dev.groknull.bpmner.api.BpmnTerminateEventDefinition as ApiBpmnTerminateEventDefinition
import dev.groknull.bpmner.api.BpmnTimerEventDefinition as ApiBpmnTimerEventDefinition
import dev.groknull.bpmner.api.BpmnUserTask as ApiBpmnUserTask
import dev.groknull.bpmner.api.ClarificationExchange as ApiClarificationExchange

/**
 * Asserts that every concrete `core` data class for the BPMN domain implements its specific
 * `api` interface — both the leaf interface and (transitively) the appropriate grouping
 * marker (`BpmnEvent` / `BpmnTask` / `BpmnGateway`). Failing arm = a core class that
 * declares a Jackson-annotated shape but forgot to implement the api contract the rule
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
        val none: dev.groknull.bpmner.domain.BpmnEventDefinition = BpmnNoneEventDefinition
        assertTrue(none is ApiBpmnNoneEventDefinition)

        val timer: dev.groknull.bpmner.domain.BpmnEventDefinition =
            BpmnTimerEventDefinition(timerKind = BpmnTimerKind.DURATION, expression = "PT5M")
        assertTrue(timer is ApiBpmnTimerEventDefinition)

        val message: dev.groknull.bpmner.domain.BpmnEventDefinition = BpmnMessageEventDefinition(messageRef = "m")
        assertTrue(message is ApiBpmnMessageEventDefinition)

        val signal: dev.groknull.bpmner.domain.BpmnEventDefinition = BpmnSignalEventDefinition(signalRef = "s")
        assertTrue(signal is ApiBpmnSignalEventDefinition)

        val error: dev.groknull.bpmner.domain.BpmnEventDefinition = BpmnErrorEventDefinition(errorRef = "e")
        assertTrue(error is ApiBpmnErrorEventDefinition)

        val escalation: dev.groknull.bpmner.domain.BpmnEventDefinition = BpmnEscalationEventDefinition(escalationRef = "esc")
        assertTrue(escalation is ApiBpmnEscalationEventDefinition)

        val terminate: dev.groknull.bpmner.domain.BpmnEventDefinition = BpmnTerminateEventDefinition
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
        val coreMi = dev.groknull.bpmner.domain.MultiInstanceLoopCharacteristics(
            mode = MultiInstanceMode.PARALLEL,
            collectionDescription = "each reviewer",
        )
        assertTrue(coreMi as Any is MultiInstanceLoopCharacteristics)

        val annotation = dev.groknull.bpmner.domain.BpmnTextAnnotation(id = "ta", text = "note")
        assertTrue(annotation as Any is BpmnTextAnnotation)

        val association = dev.groknull.bpmner.domain.BpmnAssociation(id = "as", sourceRef = "a", targetRef = "ta")
        assertTrue(association as Any is BpmnAssociation)

        // The cross-cutting `multiInstance` field is reachable through the api BpmnTask marker.
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
