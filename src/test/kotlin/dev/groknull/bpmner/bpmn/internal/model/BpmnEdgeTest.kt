/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.bpmn.internal.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BpmnEdgeTest {
    @Test
    fun `isDefault defaults to false`() {
        val edge = BpmnEdge(id = "Flow_1", sourceRef = "A", targetRef = "B")
        assertFalse(edge.isDefault)
    }

    @Test
    fun `isDefault with null conditionExpression constructs`() {
        val edge =
            BpmnEdge(
                id = "Flow_1",
                sourceRef = "Gateway_1",
                targetRef = "Task_fallback",
                isDefault = true,
            )
        assertTrue(edge.isDefault)
        assertEquals(null, edge.conditionExpression)
    }

    @Test
    fun `isDefault with blank conditionExpression constructs`() {
        // blank conditionExpression is functionally equivalent to absent; init treats it as such.
        val edge =
            BpmnEdge(
                id = "Flow_1",
                sourceRef = "Gateway_1",
                targetRef = "Task_fallback",
                conditionExpression = "   ",
                isDefault = true,
            )
        assertTrue(edge.isDefault)
    }

    @Test
    fun `isDefault with non-blank conditionExpression rejects`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                BpmnEdge(
                    id = "Flow_1",
                    sourceRef = "Gateway_1",
                    targetRef = "Task_fallback",
                    conditionExpression = "score < 600",
                    isDefault = true,
                )
            }
        assertEquals(
            "BpmnEdge Flow_1: default edge must not carry a condition expression",
            ex.message,
        )
    }
}
