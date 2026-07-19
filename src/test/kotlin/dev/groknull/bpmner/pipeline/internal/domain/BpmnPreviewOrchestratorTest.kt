/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.pipeline.internal.domain

import dev.groknull.bpmner.pipeline.internal.adapter.outbound.browser.BrowserOpenOutcome
import dev.groknull.bpmner.pipeline.internal.domain.BpmnPreviewOrchestrator.PreviewResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class BpmnPreviewOrchestratorTest {

    private fun orchestrator(
        previewReturns: Path? = null,
        browserOutcome: BrowserOpenOutcome = BrowserOpenOutcome.Opened,
        promptAnswer: Boolean = true,
    ) = BpmnPreviewOrchestrator(
        previewWriter = { bpmnPath ->
            previewReturns ?: error("writePreview called unexpectedly for $bpmnPath")
        },
        browserOpenPort = { _ -> browserOutcome },
        previewPrompt = { promptAnswer },
    )

    @Test
    fun `user declines or non-interactive - returns Skipped without writing preview`() {
        val orc = BpmnPreviewOrchestrator(
            previewWriter = { _ -> error("must not write preview when prompt declines") },
            browserOpenPort = { _ -> error("must not open browser when prompt declines") },
            previewPrompt = { false },
        )
        assertEquals(PreviewResult.Skipped, orc.runPreviewFlow("output.bpmn"))
    }

    @Test
    fun `blank file name - returns Skipped`() {
        val orc = orchestrator()
        assertEquals(PreviewResult.Skipped, orc.runPreviewFlow(""))
        assertEquals(PreviewResult.Skipped, orc.runPreviewFlow("   "))
    }

    @Test
    fun `resolved path does not exist - returns Skipped`() {
        val orc = orchestrator()
        // /nonexistent/path.bpmn won't exist on disk
        assertEquals(PreviewResult.Skipped, orc.runPreviewFlow("/nonexistent/missing.bpmn"))
    }

    @Test
    fun `preview writer throws - returns WriteFailed with bpmn path and reason`(@TempDir tempDir: Path) {
        val bpmnFile = tempDir.resolve("output.bpmn").also { it.toFile().writeText("<definitions/>") }
        val orc = BpmnPreviewOrchestrator(
            previewWriter = { _ -> throw IllegalArgumentException("disk full") },
            browserOpenPort = { _ -> error("must not open browser when writer fails") },
            previewPrompt = { true },
        )
        val result = orc.runPreviewFlow(bpmnFile.toString())
        assertInstanceOf(PreviewResult.WriteFailed::class.java, result)
        val writeFailed = result as PreviewResult.WriteFailed
        assertEquals(bpmnFile, writeFailed.bpmnPath)
        assertTrue(writeFailed.reason.contains("disk full"), "reason was: ${writeFailed.reason}")
        assertTrue(writeFailed.reason.contains("Preview write failed"), "reason was: ${writeFailed.reason}")
    }

    @Test
    fun `browser Opened - returns Opened with preview path`(@TempDir tempDir: Path) {
        val bpmnFile = tempDir.resolve("output.bpmn").also { it.toFile().writeText("<definitions/>") }
        val previewFile = tempDir.resolve("output.preview.html")
        val orc = orchestrator(previewReturns = previewFile, browserOutcome = BrowserOpenOutcome.Opened)

        val result = orc.runPreviewFlow(bpmnFile.toString())

        assertInstanceOf(PreviewResult.Opened::class.java, result)
        assertEquals(previewFile, (result as PreviewResult.Opened).previewPath)
    }

    @Test
    fun `browser Failed - returns Fallback with preview path and reason`(@TempDir tempDir: Path) {
        val bpmnFile = tempDir.resolve("output.bpmn").also { it.toFile().writeText("<definitions/>") }
        val previewFile = tempDir.resolve("output.preview.html")
        val orc = orchestrator(previewReturns = previewFile, browserOutcome = BrowserOpenOutcome.Failed("exit 1"))

        val result = orc.runPreviewFlow(bpmnFile.toString())

        assertInstanceOf(PreviewResult.Fallback::class.java, result)
        val fallback = result as PreviewResult.Fallback
        assertEquals(previewFile, fallback.previewPath)
        assertTrue(fallback.reason.contains("exit 1"), "reason was: ${fallback.reason}")
        assertTrue(fallback.reason.contains("Browser launch failed"), "reason was: ${fallback.reason}")
    }

    @Test
    fun `relative file name resolves to cwd-relative path`() {
        // A relative path that doesn't exist → Skipped (not a crash)
        val orc = orchestrator()
        assertEquals(PreviewResult.Skipped, orc.runPreviewFlow("relative-output.bpmn"))
    }
}
