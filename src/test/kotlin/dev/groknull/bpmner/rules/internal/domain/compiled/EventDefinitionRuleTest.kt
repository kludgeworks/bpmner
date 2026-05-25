/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.compiled

import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.BpmnTimerKind
import dev.groknull.bpmner.api.RuleSeverity
import dev.groknull.bpmner.core.BpmnBoundaryEvent
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnErrorEventDefinition
import dev.groknull.bpmner.core.BpmnErrorRef
import dev.groknull.bpmner.core.BpmnEscalationEventDefinition
import dev.groknull.bpmner.core.BpmnEscalationRef
import dev.groknull.bpmner.core.BpmnIntermediateCatchEvent
import dev.groknull.bpmner.core.BpmnIntermediateThrowEvent
import dev.groknull.bpmner.core.BpmnMessageEventDefinition
import dev.groknull.bpmner.core.BpmnMessageRef
import dev.groknull.bpmner.core.BpmnNoneEventDefinition
import dev.groknull.bpmner.core.BpmnSignalEventDefinition
import dev.groknull.bpmner.core.BpmnSignalRef
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnTimerEventDefinition
import dev.groknull.bpmner.core.BpmnUserTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Suppress("TooManyFunctions") // 12 cases — one per of the 9 diagnostic codes + 3 negative/structural paths
class EventDefinitionRuleTest {
    private val rule = EventDefinitionRule()

    @Test
    fun `intermediate catch with NONE event definition emits def-missing-event-def`() {
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes =
                        listOf(
                            BpmnStartEvent(id = "s", name = "Started"),
                            BpmnIntermediateCatchEvent(id = "ic", name = "Catch", eventDefinition = BpmnNoneEventDefinition),
                            BpmnEndEvent(id = "e", name = "Done"),
                        ),
                    sequences =
                        listOf(
                            BpmnEdge(id = "f1", sourceRef = "s", targetRef = "ic"),
                            BpmnEdge(id = "f2", sourceRef = "ic", targetRef = "e"),
                        ),
                ),
            )

        val diag = rule.evaluate(ctx).single()
        assertEquals("def-missing-event-def", diag.diagnosticCode)
        assertEquals("def-event-definitions", diag.ruleId)
        assertEquals(RuleSeverity.ERROR, diag.severity)
        assertEquals("intermediate catch event ic must declare an event definition", diag.message)
    }

    @Test
    fun `intermediate throw with NONE event definition emits def-missing-event-def`() {
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes =
                        listOf(
                            BpmnStartEvent(id = "s", name = "Started"),
                            BpmnIntermediateThrowEvent(id = "it", name = "Throw", eventDefinition = BpmnNoneEventDefinition),
                            BpmnEndEvent(id = "e", name = "Done"),
                        ),
                    sequences =
                        listOf(
                            BpmnEdge(id = "f1", sourceRef = "s", targetRef = "it"),
                            BpmnEdge(id = "f2", sourceRef = "it", targetRef = "e"),
                        ),
                ),
            )

        val diag = rule.evaluate(ctx).single()
        assertEquals("def-missing-event-def", diag.diagnosticCode)
        assertEquals("intermediate throw event it must declare an event definition", diag.message)
    }

    @Test
    fun `boundary event with NONE event definition emits def-missing-event-def`() {
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes =
                        listOf(
                            BpmnStartEvent(id = "s", name = "Started"),
                            BpmnUserTask(id = "task", name = "Work"),
                            BpmnBoundaryEvent(
                                id = "be",
                                name = "Boundary",
                                attachedToRef = "task",
                                eventDefinition = BpmnNoneEventDefinition,
                            ),
                            BpmnEndEvent(id = "e", name = "Done"),
                        ),
                    sequences =
                        listOf(
                            BpmnEdge(id = "f1", sourceRef = "s", targetRef = "task"),
                            BpmnEdge(id = "f2", sourceRef = "task", targetRef = "e"),
                            BpmnEdge(id = "f3", sourceRef = "be", targetRef = "e"),
                        ),
                ),
            )

        val diag = rule.evaluate(ctx).single { it.diagnosticCode == "def-missing-event-def" }
        assertEquals("boundary event be must declare an event definition", diag.message)
    }

    @Test
    fun `boundary event with blank attachedToRef emits def-missing-attached-to`() {
        val timer = BpmnTimerEventDefinition(timerKind = BpmnTimerKind.DURATION, expression = "PT5M")
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes =
                        listOf(
                            BpmnStartEvent(id = "s", name = "Started"),
                            BpmnUserTask(id = "task", name = "Work"),
                            BpmnBoundaryEvent(id = "be", name = "On timeout", attachedToRef = " ", eventDefinition = timer),
                            BpmnEndEvent(id = "e", name = "Done"),
                        ),
                    sequences =
                        listOf(
                            BpmnEdge(id = "f1", sourceRef = "s", targetRef = "task"),
                            BpmnEdge(id = "f2", sourceRef = "task", targetRef = "e"),
                            BpmnEdge(id = "f3", sourceRef = "be", targetRef = "e"),
                        ),
                ),
            )

        val diag = rule.evaluate(ctx).single()
        assertEquals("def-missing-attached-to", diag.diagnosticCode)
        assertEquals("boundary event be is missing the required attachedToRef attribute", diag.message)
    }

    @Test
    fun `boundary event with unresolved attachedToRef emits def-invalid-attached-to`() {
        val timer = BpmnTimerEventDefinition(timerKind = BpmnTimerKind.DURATION, expression = "PT5M")
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes =
                        listOf(
                            BpmnStartEvent(id = "s", name = "Started"),
                            BpmnUserTask(id = "task", name = "Work"),
                            BpmnBoundaryEvent(id = "be", name = "On timeout", attachedToRef = "missing", eventDefinition = timer),
                            BpmnEndEvent(id = "e", name = "Done"),
                        ),
                    sequences =
                        listOf(
                            BpmnEdge(id = "f1", sourceRef = "s", targetRef = "task"),
                            BpmnEdge(id = "f2", sourceRef = "task", targetRef = "e"),
                            BpmnEdge(id = "f3", sourceRef = "be", targetRef = "e"),
                        ),
                ),
            )

        val diag = rule.evaluate(ctx).single()
        assertEquals("def-invalid-attached-to", diag.diagnosticCode)
        assertEquals(
            "boundary event be attachedToRef 'missing' does not match any node id",
            diag.message,
        )
    }

    @Test
    fun `boundary event attached to a non-task emits def-non-task-attached-to`() {
        val timer = BpmnTimerEventDefinition(timerKind = BpmnTimerKind.DURATION, expression = "PT5M")
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes =
                        listOf(
                            BpmnStartEvent(id = "s", name = "Started"),
                            BpmnUserTask(id = "task", name = "Work"),
                            BpmnBoundaryEvent(id = "be", name = "On timeout", attachedToRef = "s", eventDefinition = timer),
                            BpmnEndEvent(id = "e", name = "Done"),
                        ),
                    sequences =
                        listOf(
                            BpmnEdge(id = "f1", sourceRef = "s", targetRef = "task"),
                            BpmnEdge(id = "f2", sourceRef = "task", targetRef = "e"),
                            BpmnEdge(id = "f3", sourceRef = "be", targetRef = "e"),
                        ),
                ),
            )

        val diag = rule.evaluate(ctx).single()
        assertEquals("def-non-task-attached-to", diag.diagnosticCode)
        assertEquals("boundary event be attachedToRef 's' must reference an attachable activity", diag.message)
    }

    @Test
    fun `timer event with blank expression emits def-missing-timer-expr`() {
        val blankTimer = BpmnTimerEventDefinition(timerKind = BpmnTimerKind.DURATION, expression = " ")
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes =
                        listOf(
                            BpmnStartEvent(id = "s", name = "Started"),
                            BpmnIntermediateCatchEvent(id = "ic", name = "Wait", eventDefinition = blankTimer),
                            BpmnEndEvent(id = "e", name = "Done"),
                        ),
                    sequences =
                        listOf(
                            BpmnEdge(id = "f1", sourceRef = "s", targetRef = "ic"),
                            BpmnEdge(id = "f2", sourceRef = "ic", targetRef = "e"),
                        ),
                ),
            )

        val diag = rule.evaluate(ctx).single()
        assertEquals("def-missing-timer-expr", diag.diagnosticCode)
        assertEquals("event ic timer definition expression must not be blank", diag.message)
    }

    @Test
    fun `message event with unresolved messageRef emits def-invalid-message-ref`() {
        val msgEvent = BpmnMessageEventDefinition(messageRef = "m-missing")
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes =
                        listOf(
                            BpmnStartEvent(id = "s", name = "Started"),
                            BpmnIntermediateCatchEvent(id = "ic", name = "Wait", eventDefinition = msgEvent),
                            BpmnEndEvent(id = "e", name = "Done"),
                        ),
                    sequences =
                        listOf(
                            BpmnEdge(id = "f1", sourceRef = "s", targetRef = "ic"),
                            BpmnEdge(id = "f2", sourceRef = "ic", targetRef = "e"),
                        ),
                    messages = listOf(BpmnMessageRef(id = "m-known", name = "Known")),
                ),
            )

        val diag = rule.evaluate(ctx).single()
        assertEquals("def-invalid-message-ref", diag.diagnosticCode)
        assertEquals("event ic messageRef 'm-missing' does not match any message catalog id", diag.message)
    }

    @Test
    fun `signal event with unresolved signalRef emits def-invalid-signal-ref`() {
        val signalEvent = BpmnSignalEventDefinition(signalRef = "sig-missing")
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes =
                        listOf(
                            BpmnStartEvent(id = "s", name = "Started"),
                            BpmnIntermediateCatchEvent(id = "ic", name = "Wait", eventDefinition = signalEvent),
                            BpmnEndEvent(id = "e", name = "Done"),
                        ),
                    sequences =
                        listOf(
                            BpmnEdge(id = "f1", sourceRef = "s", targetRef = "ic"),
                            BpmnEdge(id = "f2", sourceRef = "ic", targetRef = "e"),
                        ),
                    signals = listOf(BpmnSignalRef(id = "sig-known", name = "Known")),
                ),
            )

        val diag = rule.evaluate(ctx).single()
        assertEquals("def-invalid-signal-ref", diag.diagnosticCode)
        assertEquals("event ic signalRef 'sig-missing' does not match any signal catalog id", diag.message)
    }

    @Test
    fun `error event with unresolved errorRef emits def-invalid-error-ref`() {
        val errEvent = BpmnErrorEventDefinition(errorRef = "err-missing")
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes =
                        listOf(
                            BpmnStartEvent(id = "s", name = "Started"),
                            BpmnIntermediateCatchEvent(id = "ic", name = "Wait", eventDefinition = errEvent),
                            BpmnEndEvent(id = "e", name = "Done"),
                        ),
                    sequences =
                        listOf(
                            BpmnEdge(id = "f1", sourceRef = "s", targetRef = "ic"),
                            BpmnEdge(id = "f2", sourceRef = "ic", targetRef = "e"),
                        ),
                    errors = listOf(BpmnErrorRef(id = "err-known", code = "EKNOWN")),
                ),
            )

        val diag = rule.evaluate(ctx).single()
        assertEquals("def-invalid-error-ref", diag.diagnosticCode)
        assertEquals("event ic errorRef 'err-missing' does not match any error catalog id", diag.message)
    }

    @Test
    fun `escalation event with unresolved escalationRef emits def-invalid-escalation-ref`() {
        val escEvent = BpmnEscalationEventDefinition(escalationRef = "esc-missing")
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes =
                        listOf(
                            BpmnStartEvent(id = "s", name = "Started"),
                            BpmnIntermediateCatchEvent(id = "ic", name = "Wait", eventDefinition = escEvent),
                            BpmnEndEvent(id = "e", name = "Done"),
                        ),
                    sequences =
                        listOf(
                            BpmnEdge(id = "f1", sourceRef = "s", targetRef = "ic"),
                            BpmnEdge(id = "f2", sourceRef = "ic", targetRef = "e"),
                        ),
                    escalations = listOf(BpmnEscalationRef(id = "esc-known", code = "EKNOWN")),
                ),
            )

        val diag = rule.evaluate(ctx).single()
        assertEquals("def-invalid-escalation-ref", diag.diagnosticCode)
        assertEquals(
            "event ic escalationRef 'esc-missing' does not match any escalation catalog id",
            diag.message,
        )
    }

    @Test
    fun `clean boundary event attached to task with valid timer emits no diagnostic`() {
        val timer = BpmnTimerEventDefinition(timerKind = BpmnTimerKind.DURATION, expression = "PT5M")
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes =
                        listOf(
                            BpmnStartEvent(id = "s", name = "Started"),
                            BpmnUserTask(id = "task", name = "Work"),
                            BpmnBoundaryEvent(id = "be", name = "On timeout", attachedToRef = "task", eventDefinition = timer),
                            BpmnEndEvent(id = "e", name = "Done"),
                        ),
                    sequences =
                        listOf(
                            BpmnEdge(id = "f1", sourceRef = "s", targetRef = "task"),
                            BpmnEdge(id = "f2", sourceRef = "task", targetRef = "e"),
                            BpmnEdge(id = "f3", sourceRef = "be", targetRef = "e"),
                        ),
                ),
            )

        assertTrue(rule.evaluate(ctx).isEmpty())
    }
}
