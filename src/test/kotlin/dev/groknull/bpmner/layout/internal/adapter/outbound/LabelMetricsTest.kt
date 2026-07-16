/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import dev.groknull.bpmner.layout.internal.adapter.outbound.placement.LabelMetrics
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [LabelMetrics] frozen advance-width table (AD-557-15).
 *
 * Anchors the table's correctness against known bpmn-js-measured label widths
 * and verifies the non-ASCII fallback contract.
 */
class LabelMetricsTest {

    private val epsilon = 0.01

    @Test
    fun `known single characters have correct advance widths`() {
        // 'A' = 8.00390625, 'i' = 2.666015625, 'm' = 9.99609375 (narrow vs wide glyphs)
        assertEquals(8.00390625, LabelMetrics.advance('A'), "advance('A')")
        assertEquals(2.666015625, LabelMetrics.advance('i'), "advance('i')")
        assertEquals(9.99609375, LabelMetrics.advance('m'), "advance('m')")
        assertEquals(3.333984375, LabelMetrics.advance(' '), "advance(' ')")
    }

    @Test
    fun `Order Confirmation width matches Chromium measurement`() {
        // "Order Confirmation" measured in real Chromium at 12px Arial = ~95px
        val w = LabelMetrics.width("Order Confirmation")
        assertTrue(w > 90.0 && w < 105.0, "Expected ~95px for 'Order Confirmation', got $w")
    }

    @Test
    fun `Shipment Notification width fits in 120px edge box`() {
        // This label must fit on one line in a 120px edge box to match bpmn-js behaviour
        val w = LabelMetrics.width("Shipment Notification")
        assertTrue(w < 120.0, "Expected 'Shipment Notification' ($w) to fit in 120px box")
    }

    @Test
    fun `non-ASCII character falls back to DEFAULT_ADVANCE`() {
        val fallback = LabelMetrics.DEFAULT_ADVANCE
        assertEquals(fallback, LabelMetrics.advance('é'), "Non-ASCII should use DEFAULT_ADVANCE")
        assertEquals(fallback, LabelMetrics.advance('中'), "Non-ASCII should use DEFAULT_ADVANCE")
    }

    @Test
    fun `width of empty string is 0`() {
        assertEquals(0.0, LabelMetrics.width(""), "Empty string must have zero width")
    }

    @Test
    fun `width sums individual advances`() {
        val expected = LabelMetrics.advance('A') + LabelMetrics.advance('B') + LabelMetrics.advance('C')
        val actual = LabelMetrics.width("ABC")
        assertEquals(expected, actual, epsilon, "width('ABC') must equal sum of advances")
    }

    @Test
    fun `LINE_HEIGHT is 14`() {
        // Measured in real Chromium; not a guess
        assertEquals(14.0, LabelMetrics.LINE_HEIGHT)
    }
}
