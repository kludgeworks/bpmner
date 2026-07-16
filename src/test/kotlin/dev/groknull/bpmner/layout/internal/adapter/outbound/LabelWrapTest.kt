/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import dev.groknull.bpmner.layout.internal.adapter.outbound.placement.LabelWrap
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [LabelWrap] — the diagram-js@15.14.0 semantic wrap port (AD-557-15).
 *
 * Each assertion uses a line count produced by the real diagram-js `layoutText`
 * function in actual Chromium (Playwright), so these tests pin the JVM port to the
 * oracle's exact behaviour for the blocker examples and edge cases.
 *
 * Line counts verified against diagram-js@15.14.0 in real Chromium at
 * `font = "12px Arial, sans-serif"`, padding=0.
 */
class LabelWrapTest {

    @Test
    fun `External System fits on one line at box 90 and 120`() {
        assertEquals(1, LabelWrap.lineCount("External System", 90.0), "box90")
        assertEquals(1, LabelWrap.lineCount("External System", 120.0), "box120")
    }

    @Test
    fun `Internal Process fits on one line at box 90 and 120`() {
        assertEquals(1, LabelWrap.lineCount("Internal Process", 90.0), "box90")
        assertEquals(1, LabelWrap.lineCount("Internal Process", 120.0), "box120")
    }

    @Test
    fun `Shipment Notification wraps to 2 lines at box 90, 1 at box 120`() {
        assertEquals(2, LabelWrap.lineCount("Shipment Notification", 90.0), "box90")
        assertEquals(1, LabelWrap.lineCount("Shipment Notification", 120.0), "box120")
    }

    @Test
    fun `Order Confirmation wraps to 2 lines at box 90, 1 at box 120`() {
        assertEquals(2, LabelWrap.lineCount("Order Confirmation", 90.0), "box90")
        assertEquals(1, LabelWrap.lineCount("Order Confirmation", 120.0), "box120")
    }

    @Test
    fun `Identity verification passed wraps correctly`() {
        assertEquals(3, LabelWrap.lineCount("Identity verification passed?", 90.0), "box90")
        assertEquals(2, LabelWrap.lineCount("Identity verification passed?", 120.0), "box120")
    }

    @Test
    fun `long phrase wraps correctly at both box widths`() {
        val text = "Order rejected due to failed identity check"
        assertEquals(3, LabelWrap.lineCount(text, 90.0), "box90")
        assertEquals(2, LabelWrap.lineCount(text, 120.0), "box120")
    }

    @Test
    fun `hyphenated word fits on one line at both boxes`() {
        assertEquals(1, LabelWrap.lineCount("Auto-generate", 90.0), "box90")
        assertEquals(1, LabelWrap.lineCount("Auto-generate", 120.0), "box120")
    }

    @Test
    fun `single long word without spaces force-cuts`() {
        // "Superlongwordwithoutspaces" measured in Chromium → 2 lines at box 90 and 120
        assertEquals(2, LabelWrap.lineCount("Superlongwordwithoutspaces", 90.0), "box90")
        assertEquals(2, LabelWrap.lineCount("Superlongwordwithoutspaces", 120.0), "box120")
    }

    @Test
    fun `single short word fits in one line`() {
        assertEquals(1, LabelWrap.lineCount("Start", 90.0), "box90")
        assertEquals(1, LabelWrap.lineCount("Start", 120.0), "box120")
    }

    @Test
    fun `blank text returns zero lines`() {
        assertEquals(0, LabelWrap.lineCount("", 90.0), "empty")
        assertEquals(0, LabelWrap.lineCount("   ", 90.0), "whitespace-only")
    }
}
