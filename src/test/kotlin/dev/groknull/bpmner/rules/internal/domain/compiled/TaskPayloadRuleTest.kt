/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.compiled

import dev.groknull.bpmner.bpmn.BpmnDefinitionContext
import dev.groknull.bpmner.bpmn.RuleSeverity
import dev.groknull.bpmner.bpmn.internal.model.BpmnBusinessRuleTask
import dev.groknull.bpmner.bpmn.internal.model.BpmnDefinition
import dev.groknull.bpmner.bpmn.internal.model.BpmnEdge
import dev.groknull.bpmner.bpmn.internal.model.BpmnEndEvent
import dev.groknull.bpmner.bpmn.internal.model.BpmnMessageRef
import dev.groknull.bpmner.bpmn.internal.model.BpmnReceiveTask
import dev.groknull.bpmner.bpmn.internal.model.BpmnSendTask
import dev.groknull.bpmner.bpmn.internal.model.BpmnStartEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TaskPayloadRuleTest {
    private val rule = TaskPayloadRule()

    @Test
    fun `unresolved sendTask messageRef emits def-invalid-task-message-ref`() {
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes =
                    listOf(
                        BpmnStartEvent(id = "s", name = "Started"),
                        BpmnSendTask(id = "send", name = "Notify", messageRef = "m-missing"),
                        BpmnEndEvent(id = "e", name = "Done"),
                    ),
                    sequences =
                    listOf(
                        BpmnEdge(id = "f1", sourceRef = "s", targetRef = "send"),
                        BpmnEdge(id = "f2", sourceRef = "send", targetRef = "e"),
                    ),
                    messages = listOf(BpmnMessageRef(id = "m-known", name = "Known")),
                ),
            )

        val diag = rule.evaluate(ctx).single()
        assertEquals("def-invalid-task-message-ref", diag.diagnosticCode)
        assertEquals("def-task-payloads", diag.ruleId)
        assertEquals(RuleSeverity.ERROR, diag.severity)
        assertEquals("sendTask send messageRef 'm-missing' does not match any message catalog id", diag.message)
        assertEquals("send", diag.elementId)
    }

    @Test
    fun `sendTask with blank messageRef emits def-missing-message-ref`() {
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes =
                    listOf(
                        BpmnStartEvent(id = "s", name = "Started"),
                        BpmnSendTask(id = "send", name = "Notify", messageRef = " "),
                        BpmnEndEvent(id = "e", name = "Done"),
                    ),
                    sequences =
                    listOf(
                        BpmnEdge(id = "f1", sourceRef = "s", targetRef = "send"),
                        BpmnEdge(id = "f2", sourceRef = "send", targetRef = "e"),
                    ),
                ),
            )

        val diag = rule.evaluate(ctx).single()
        assertEquals("def-missing-message-ref", diag.diagnosticCode)
        assertEquals("def-task-payloads", diag.ruleId)
        assertEquals(RuleSeverity.ERROR, diag.severity)
        assertEquals("sendTask send is missing the required messageRef attribute", diag.message)
        assertEquals("send", diag.elementId)
    }

    @Test
    fun `unresolved receiveTask messageRef emits def-invalid-task-message-ref`() {
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes =
                    listOf(
                        BpmnStartEvent(id = "s", name = "Started"),
                        BpmnReceiveTask(id = "recv", name = "Wait", messageRef = "m-missing"),
                        BpmnEndEvent(id = "e", name = "Done"),
                    ),
                    sequences =
                    listOf(
                        BpmnEdge(id = "f1", sourceRef = "s", targetRef = "recv"),
                        BpmnEdge(id = "f2", sourceRef = "recv", targetRef = "e"),
                    ),
                ),
            )

        val diag = rule.evaluate(ctx).single()
        assertEquals("def-invalid-task-message-ref", diag.diagnosticCode)
        assertEquals("receiveTask recv messageRef 'm-missing' does not match any message catalog id", diag.message)
    }

    @Test
    fun `businessRuleTask with blank decisionRef emits def-missing-decision-ref`() {
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes =
                    listOf(
                        BpmnStartEvent(id = "s", name = "Started"),
                        BpmnBusinessRuleTask(id = "br", name = "Apply rules", decisionRef = " "),
                        BpmnEndEvent(id = "e", name = "Done"),
                    ),
                    sequences =
                    listOf(
                        BpmnEdge(id = "f1", sourceRef = "s", targetRef = "br"),
                        BpmnEdge(id = "f2", sourceRef = "br", targetRef = "e"),
                    ),
                ),
            )

        val diag = rule.evaluate(ctx).single()
        assertEquals("def-missing-decision-ref", diag.diagnosticCode)
        assertEquals("businessRuleTask br is missing the required decisionRef attribute", diag.message)
        assertEquals("br", diag.elementId)
    }

    @Test
    fun `sendTask with resolved messageRef emits no diagnostic`() {
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes =
                    listOf(
                        BpmnStartEvent(id = "s", name = "Started"),
                        BpmnSendTask(id = "send", name = "Notify", messageRef = "m-known"),
                        BpmnEndEvent(id = "e", name = "Done"),
                    ),
                    sequences =
                    listOf(
                        BpmnEdge(id = "f1", sourceRef = "s", targetRef = "send"),
                        BpmnEdge(id = "f2", sourceRef = "send", targetRef = "e"),
                    ),
                    messages = listOf(BpmnMessageRef(id = "m-known", name = "Known")),
                ),
            )

        assertTrue(rule.evaluate(ctx).isEmpty())
    }

    @Test
    fun `clean definition with no task-payload errors emits no diagnostic`() {
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes =
                    listOf(
                        BpmnStartEvent(id = "s", name = "Started"),
                        BpmnBusinessRuleTask(id = "br", name = "Apply", decisionRef = "d1"),
                        BpmnEndEvent(id = "e", name = "Done"),
                    ),
                    sequences =
                    listOf(
                        BpmnEdge(id = "f1", sourceRef = "s", targetRef = "br"),
                        BpmnEdge(id = "f2", sourceRef = "br", targetRef = "e"),
                    ),
                ),
            )

        assertTrue(rule.evaluate(ctx).isEmpty())
    }
}
