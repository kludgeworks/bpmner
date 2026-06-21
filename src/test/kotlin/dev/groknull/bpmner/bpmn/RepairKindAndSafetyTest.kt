/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.bpmn

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Smoke test that the move of `RepairKind` and `RepairSafety` from `validation/` to `bpmn/`
 * preserved values and member functions. Companion to PR-4 of #210.
 */
class RepairKindAndSafetyTest {
    @Test
    fun `RepairKind retains 5 values`() {
        assertEquals(5, enumValues<RepairKind>().size)
    }

    @Test
    fun `RepairKind isLocal returns true for local fix kinds`() {
        assertTrue(RepairKind.LOCAL_MODEL_FIX.isLocal())
        assertTrue(RepairKind.LOCAL_XML_FIX.isLocal())
        assertFalse(RepairKind.LLM_MODEL_PATCH.isLocal())
        assertFalse(RepairKind.LLM_XML_REWRITE.isLocal())
        assertFalse(RepairKind.UNFIXABLE.isLocal())
    }

    @Test
    fun `RepairKind isLlm returns true for LLM-driven kinds`() {
        assertTrue(RepairKind.LLM_MODEL_PATCH.isLlm())
        assertTrue(RepairKind.LLM_XML_REWRITE.isLlm())
        assertFalse(RepairKind.LOCAL_MODEL_FIX.isLlm())
        assertFalse(RepairKind.LOCAL_XML_FIX.isLlm())
        assertFalse(RepairKind.UNFIXABLE.isLlm())
    }

    @Test
    fun `RepairSafety retains 3 values`() {
        assertEquals(3, enumValues<RepairSafety>().size)
    }

    @Test
    fun `RepairMetadata defaults are typed enums`() {
        val meta = RepairMetadata()
        assertEquals(RepairKind.LLM_MODEL_PATCH, meta.kind)
        assertEquals(RepairSafety.LLM_ONLY, meta.safety)
    }
}
