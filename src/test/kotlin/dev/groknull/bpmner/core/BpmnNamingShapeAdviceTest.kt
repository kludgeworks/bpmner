/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BpmnNamingShapeAdviceTest {
    @Test
    fun `every current BpmnNode subtype has advice`() {
        // Exhaustive coverage: if a new sealed subtype is added without updating the advice,
        // the `when` in BpmnNamingShapeAdvice.adviceFor will fail to compile and this test
        // will not compile either — both are the desired forcing function.
        val subjects: List<BpmnNode> =
            listOf(
                BpmnStartEvent("s", "Start"),
                BpmnEndEvent("e", "End"),
                BpmnUserTask("ut", "User task"),
                BpmnServiceTask("st", "Service task"),
                BpmnScriptTask("sct", "Script task"),
                BpmnBusinessRuleTask("brt", "Business rule task", decisionRef = "Decision_1"),
                BpmnSendTask("set", "Send task", messageRef = "Message_1"),
                BpmnReceiveTask("ret", "Receive task", messageRef = "Message_1"),
                BpmnManualTask("mat", "Manual task"),
                BpmnExclusiveGateway("xg", "X?"),
                BpmnParallelGateway("pg", "PG"),
                BpmnIntermediateCatchEvent("ice", "Caught", eventDefinition = BpmnNoneEventDefinition),
                BpmnIntermediateThrowEvent("ite", "Thrown", eventDefinition = BpmnNoneEventDefinition),
                BpmnBoundaryEvent("be", "Boundary", attachedToRef = "ut", eventDefinition = BpmnNoneEventDefinition),
            )
        subjects.forEach { node ->
            val advice = BpmnNamingShapeAdvice.adviceFor(node)
            assertNotNull(advice, "every BpmnNode subtype should have advice; missing for ${node::class.simpleName}")
            assertTrue(advice.examples.isNotEmpty(), "advice must include at least one example")
            assertTrue(advice.antiExamples.isNotEmpty(), "advice must include at least one anti-example")
        }
    }

    @Test
    fun `end-event advice describes past-tense state shape`() {
        val advice = BpmnNamingShapeAdvice.adviceFor(BpmnEndEvent("e", "End"))
        assertNotNull(advice)
        assertTrue(advice.shape.contains("past-tense", ignoreCase = true))
        assertTrue(advice.examples.any { it.lowercase().contains("issued") || it.lowercase().contains("written") })
        assertTrue(advice.antiExamples.any { it.lowercase().startsWith("issue") || it.lowercase().startsWith("write") })
    }

    @Test
    fun `task advice describes imperative verb-object`() {
        val userAdvice = BpmnNamingShapeAdvice.adviceFor(BpmnUserTask("ut", "x"))
        val serviceAdvice = BpmnNamingShapeAdvice.adviceFor(BpmnServiceTask("st", "x"))
        assertNotNull(userAdvice)
        assertNotNull(serviceAdvice)
        assertTrue(userAdvice.shape.contains("verb-object", ignoreCase = true))
        assertTrue(serviceAdvice.shape.contains("verb-object", ignoreCase = true))
    }

    @Test
    fun `allAdvice returns every advice entry`() {
        val all = BpmnNamingShapeAdvice.allAdvice()
        // 14 = start, intermediate-catch, intermediate-throw, boundary, end, user-task,
        // service-task, script-task, business-rule-task, send-task, receive-task, manual-task,
        // exclusive-gateway, parallel-gateway. Covers every current BpmnNode subtype that
        // has a naming-shape opinion.
        assertEquals(14, all.size)
        assertTrue(all.any { it.kind == "START_EVENT" })
        assertTrue(all.any { it.kind == "END_EVENT" })
        assertTrue(all.any { it.kind == "INTERMEDIATE_CATCH_EVENT" })
        assertTrue(all.any { it.kind == "INTERMEDIATE_THROW_EVENT" })
        assertTrue(all.any { it.kind == "BOUNDARY_EVENT" })
        assertTrue(all.any { it.kind == "SCRIPT_TASK" })
        assertTrue(all.any { it.kind == "BUSINESS_RULE_TASK" })
        assertTrue(all.any { it.kind == "SEND_TASK" })
        assertTrue(all.any { it.kind == "RECEIVE_TASK" })
        assertTrue(all.any { it.kind == "MANUAL_TASK" })
        assertTrue(all.any { it.kind.startsWith("EXCLUSIVE_GATEWAY") })
    }

    @Test
    fun `adviceForRule returns inclusive kind labels for rules that span multiple node kinds`() {
        // evt-event-state-name targets start, intermediate-catch, intermediate-throw, and end
        // events. Returning END_EVENT as the kind label would mislead the repair LLM when the
        // rule fires on a start or intermediate event — the synthetic EVENT advice reads
        // inclusively and the shape/examples still apply uniformly.
        val eventStateName = BpmnNamingShapeAdvice.adviceForRule("bpmner/evt-event-state-name")
        assertEquals("EVENT (start / intermediate / end)", eventStateName?.kind)
        assertTrue(eventStateName!!.shape.contains("past-participle", ignoreCase = true))
        // Examples now mix start and end shapes (Order received, Offer issued) — covers both.
        assertTrue(eventStateName.examples.any { it.contains("received", ignoreCase = true) })
        assertTrue(eventStateName.examples.any { it.contains("issued", ignoreCase = true) })

        assertEquals(
            "EVENT (start / intermediate / end)",
            BpmnNamingShapeAdvice.adviceForRule("bpmner/evt-event-state-pattern")?.kind,
        )

        // act-verb-object-name applies to both user tasks and service tasks — same shape.
        val taskVerbObject = BpmnNamingShapeAdvice.adviceForRule("bpmner/act-verb-object-name")
        assertEquals("TASK (user / service)", taskVerbObject?.kind)
        assertTrue(taskVerbObject!!.shape.contains("verb-object", ignoreCase = true))
        // Examples now mix user-task ("Validate request") and service-task ("Send notification").
        assertTrue(taskVerbObject.examples.any { it.contains("Validate") })
        assertTrue(taskVerbObject.examples.any { it.contains("Send notification") })

        // Single-kind rule is unchanged.
        assertEquals(
            "EXCLUSIVE_GATEWAY (diverging)",
            BpmnNamingShapeAdvice.adviceForRule("bpmner/gtw-diverging-gateway-question")?.kind,
        )
    }

    @Test
    fun `adviceForRule returns null for unknown rule ids`() {
        assertNull(BpmnNamingShapeAdvice.adviceForRule("bpmner/some-unrelated-rule"))
        assertNull(BpmnNamingShapeAdvice.adviceForRule(""))
    }
}
