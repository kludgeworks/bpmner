/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BpmnResultContentTest {
    @Test
    fun `outputFileName returns the last path segment`() {
        assertEquals("approval.bpmn", outputFileName("/home/u/project/approval.bpmn"))
        assertEquals("approval.bpmn", outputFileName("out/approval.bpmn"))
        assertEquals("approval.bpmn", outputFileName("approval.bpmn"))
    }

    @Test
    fun `outputFileName handles a missing file`() {
        assertEquals("(not written to a file)", outputFileName(null))
        assertEquals("(not written to a file)", outputFileName(""))
    }

    @Test
    fun `content names the output file without its path`() {
        val result =
            BpmnResult(
                outputFile = "/Users/x/project/purchase-order-approval.bpmn",
                status = BpmnGenerationStatus.GENERATED,
                xml = "<process id=\"p\"/>",
            )

        assertTrue(result.content.contains("purchase-order-approval.bpmn"), "content: ${result.content}")
        assertTrue(result.content.contains("Generated"), "content: ${result.content}")
        assertFalse(result.content.contains("/Users/x/project"), "content must not include the path: ${result.content}")
    }

    @Test
    fun `content summarises a needs-clarification result with its report`() {
        val result =
            BpmnResult(
                outputFile = null,
                status = BpmnGenerationStatus.NEEDS_CLARIFICATION,
                reportFile = "readiness.md",
            )

        assertTrue(result.content.contains("clarification"), "content: ${result.content}")
        assertTrue(result.content.contains("readiness.md"), "content: ${result.content}")
    }

    @Test
    fun `content is excluded from the JSON contract`() {
        val json =
            jacksonObjectMapper().writeValueAsString(
                BpmnResult(
                    outputFile = "out.bpmn",
                    status = BpmnGenerationStatus.GENERATED,
                    xml = "<process/>",
                ),
            )

        assertFalse(json.contains("\"content\""), "the computed content getter must not serialise: $json")
        assertTrue(json.contains("\"outputFile\""))
    }
}
