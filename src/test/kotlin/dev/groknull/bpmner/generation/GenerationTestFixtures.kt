/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation

import dev.groknull.bpmner.bpmn.internal.model.BpmnBusinessRuleTask
import dev.groknull.bpmner.bpmn.internal.model.BpmnDefinition
import dev.groknull.bpmner.bpmn.internal.model.BpmnEdge
import dev.groknull.bpmner.bpmn.internal.model.BpmnEndEvent
import dev.groknull.bpmner.bpmn.internal.model.BpmnExclusiveGateway
import dev.groknull.bpmner.bpmn.internal.model.BpmnManualTask
import dev.groknull.bpmner.bpmn.internal.model.BpmnMessageRef
import dev.groknull.bpmner.bpmn.internal.model.BpmnReceiveTask
import dev.groknull.bpmner.bpmn.internal.model.BpmnScriptTask
import dev.groknull.bpmner.bpmn.internal.model.BpmnSendTask
import dev.groknull.bpmner.bpmn.internal.model.BpmnStartEvent
import dev.groknull.bpmner.bpmn.internal.model.BpmnUserTask

/**
 * Shared test fixtures for generation package tests.
 * Extracted to eliminate CPD (copy-paste) violations across test classes in this package.
 */

/**
 * A two-path "credit-tier routing" BPMN definition used to verify isDefault/conditionExpression
 * round-trip behaviour across both the XML serialiser and deserialiser.
 */
internal fun creditTierDefinition() = BpmnDefinition(
    processId = "Process_credit",
    processName = "Credit-tier routing",
    nodes =
    listOf(
        BpmnStartEvent("StartEvent_1", "Score received"),
        BpmnExclusiveGateway("Gateway_1", "Which credit tier?"),
        BpmnUserTask("Task_fast", "Fast-track underwriting"),
        BpmnUserTask("Task_manual", "Manual review"),
        BpmnEndEvent("EndEvent_1", "Offer generated"),
    ),
    sequences =
    listOf(
        BpmnEdge("Flow_1", "StartEvent_1", "Gateway_1"),
        BpmnEdge(
            "Flow_fast",
            "Gateway_1",
            "Task_fast",
            conditionExpression = "score >= 750",
        ),
        BpmnEdge("Flow_manual", "Gateway_1", "Task_manual", isDefault = true),
        BpmnEdge("Flow_3", "Task_fast", "EndEvent_1"),
        BpmnEdge("Flow_4", "Task_manual", "EndEvent_1"),
    ),
)

/**
 * A mortgage processing workflow exercising all five additional task subtypes (script, business-rule,
 * send, receive, manual) and two message refs.
 *
 * @param processId Allows callers to vary the process ID for round-trip test isolation.
 */
internal fun mortgageProcessingDefinition(processId: String = "Process_mortgage") = BpmnDefinition(
    processId = processId,
    processName = "Mortgage processing",
    nodes =
    listOf(
        BpmnStartEvent("StartEvent_1", "Application submitted"),
        BpmnScriptTask("act-normalise", "Normalise address"),
        BpmnBusinessRuleTask("act-credit", "Evaluate credit policy", decisionRef = "credit-policy"),
        BpmnSendTask("act-decline", "Send decline notification", messageRef = "Message_Decline"),
        BpmnReceiveTask(
            "act-await-ack",
            "Customer acknowledgement received",
            messageRef = "Message_Ack",
        ),
        BpmnManualTask("act-inspect", "Inspect property"),
        BpmnEndEvent("EndEvent_1", "Application completed"),
    ),
    sequences =
    listOf(
        BpmnEdge("F1", "StartEvent_1", "act-normalise"),
        BpmnEdge("F2", "act-normalise", "act-credit"),
        BpmnEdge("F3", "act-credit", "act-decline"),
        BpmnEdge("F4", "act-decline", "act-await-ack"),
        BpmnEdge("F5", "act-await-ack", "act-inspect"),
        BpmnEdge("F6", "act-inspect", "EndEvent_1"),
    ),
    messages =
    listOf(
        BpmnMessageRef("Message_Decline", "Decline notification"),
        BpmnMessageRef("Message_Ack", "Customer acknowledgement"),
    ),
)
