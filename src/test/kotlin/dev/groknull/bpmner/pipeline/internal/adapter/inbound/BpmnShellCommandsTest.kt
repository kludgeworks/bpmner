/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.pipeline.internal.adapter.inbound

import com.embabel.agent.shell.ShellCommands
import dev.groknull.bpmner.pipeline.internal.adapter.outbound.browser.BrowserOpenOutcome
import dev.groknull.bpmner.pipeline.internal.adapter.outbound.browser.BrowserOpenPort
import dev.groknull.bpmner.pipeline.internal.adapter.outbound.preview.BpmnPreviewWriter
import dev.groknull.bpmner.pipeline.internal.domain.BpmnPreviewOrchestrator
import dev.groknull.bpmner.pipeline.internal.domain.BpmnPreviewOrchestrator.PreviewResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.ObjectProvider
import java.nio.file.Paths

class BpmnShellCommandsTest {

    /** Declining orchestrator (returns Skipped): existing generation tests never trigger a preview. */
    private val nonInteractiveOrchestrator = BpmnPreviewOrchestrator(
        previewWriter = BpmnPreviewWriter { _ -> Paths.get("preview.html") },
        browserOpenPort = BrowserOpenPort { _ -> BrowserOpenOutcome.Failed("test") },
        previewPrompt = PreviewPrompt { false },
    )

    private fun commandDelegatingTo(
        shellCommands: ShellCommands,
        orchestrator: BpmnPreviewOrchestrator = nonInteractiveOrchestrator,
    ): BpmnShellCommands {
        @Suppress("UNCHECKED_CAST")
        val provider = mock(ObjectProvider::class.java) as ObjectProvider<ShellCommands>
        `when`(provider.getObject()).thenReturn(shellCommands)
        return BpmnShellCommands(provider, orchestrator)
    }

    // ── Existing generation tests (must stay byte-for-byte unchanged in expectations) ───────────

    @Test
    fun `generate delegates to embabel execute in closed mode`() {
        val shellCommands = mock(ShellCommands::class.java)
        // Closed mode (2nd arg = false); every other flag off except the default showPlanning.
        `when`(
            shellCommands.execute("Make toast", false, false, false, false, false, false, false, true, null),
        ).thenReturn("Generated BPMN")

        val result = commandDelegatingTo(shellCommands).generate("Make toast")

        assertEquals("Generated BPMN", result)
        verify(shellCommands).execute("Make toast", false, false, false, false, false, false, false, true, null)
    }

    @Test
    fun `an explicit output file is conveyed as a directive in the intent`() {
        val shellCommands = mock(ShellCommands::class.java)
        val expectedIntent = "Make toast\n\nWrite the generated BPMN to the file: toast.bpmn"
        `when`(
            shellCommands.execute(expectedIntent, false, false, false, false, false, false, false, true, null),
        ).thenReturn("ok")

        val result = commandDelegatingTo(shellCommands).generate("Make toast", "toast.bpmn")

        assertEquals("ok", result)
        verify(shellCommands).execute(expectedIntent, false, false, false, false, false, false, false, true, null)
    }

    @Test
    fun `the written output file is echoed as the final line`() {
        val shellCommands = mock(ShellCommands::class.java)
        // Mirrors how embabel renders the result: BpmnResult.content (with the file) sits above the
        // cost/tool-usage summary, so the filename is easy to miss without a trailing echo.
        val rendered =
            """
            You asked: Make toast
            Generated BPMN → toast.bpmn (842 chars).

            LLMs used: [mistral-large-2512] across 4 calls
            Cost: ${'$'}0.04
            Tool usage:
            """.trimIndent()
        `when`(
            shellCommands.execute("Make toast", false, false, false, false, false, false, false, true, null),
        ).thenReturn(rendered)

        val result = commandDelegatingTo(shellCommands).generate("Make toast")

        assertTrue(result.endsWith("Wrote BPMN to: toast.bpmn"), "result was:\n$result")
    }

    @Test
    fun `the output marker is recovered even when embabel colourises it with ANSI escapes`() {
        val shellCommands = mock(ShellCommands::class.java)
        // Embabel's FormatProcessOutput wraps HasContent.content in ANSI SGR colour escapes; the
        // outer-wrap escape surrounds the entire line so stripping it recovers the plain prefix.
        val esc = "\u001B"
        val rendered =
            "You asked: Make toast\n" +
                "$esc[38;2;190;183;128mGenerated BPMN \u2192 toast.bpmn (842 chars).$esc[0m\n\n" +
                "LLMs used: [gpt-4.1] across 4 calls"
        `when`(
            shellCommands.execute("Make toast", false, false, false, false, false, false, false, true, null),
        ).thenReturn(rendered)

        val result = commandDelegatingTo(shellCommands).generate("Make toast")

        assertTrue(result.endsWith("Wrote BPMN to: toast.bpmn"), "result was:\n$result")
    }

    @Test
    fun `the output marker is recovered when an ANSI escape is injected mid-prefix`() {
        val shellCommands = mock(ShellCommands::class.java)
        // Root-cause path: the colour escape splits the prefix string between "Generated " and
        // "BPMN →", so a naive string match on "Generated BPMN →" fails to find the marker.
        val esc = "\u001B"
        val rendered =
            "You asked: Make toast\n" +
                "Generated $esc[38;2;190;183;128mBPMN \u2192 toast.bpmn (842 chars).$esc[0m\n\n" +
                "LLMs used: [gpt-4.1] across 4 calls"
        `when`(
            shellCommands.execute("Make toast", false, false, false, false, false, false, false, true, null),
        ).thenReturn(rendered)

        val result = commandDelegatingTo(shellCommands).generate("Make toast")

        assertTrue(result.endsWith("Wrote BPMN to: toast.bpmn"), "result was:\n$result")
    }

    @Test
    fun `no trailing line is added when nothing was generated`() {
        val shellCommands = mock(ShellCommands::class.java)
        `when`(
            shellCommands.execute("Make toast", false, false, false, false, false, false, false, true, null),
        ).thenReturn("I'm sorry. I don't know how to proceed.")

        val result = commandDelegatingTo(shellCommands).generate("Make toast")

        assertEquals("I'm sorry. I don't know how to proceed.", result)
    }

    // ── Preview flow tests (all via mocked orchestrator, no real stdin/browser/LLM) ─────────────

    private fun renderedWithMarker(fileName: String): String {
        return "You asked: Make toast\nGenerated BPMN → $fileName (842 chars).\n\nLLMs used: x"
    }

    @Test
    fun `orchestrator Skipped - output unchanged, no preview suffix`() {
        val shellCommands = mock(ShellCommands::class.java)
        val orchestrator = mock(BpmnPreviewOrchestrator::class.java)
        val rendered = renderedWithMarker("toast.bpmn")
        `when`(shellCommands.execute("Make toast", false, false, false, false, false, false, false, true, null))
            .thenReturn(rendered)
        `when`(orchestrator.runPreviewFlow("toast.bpmn")).thenReturn(PreviewResult.Skipped)

        val result = commandDelegatingTo(shellCommands, orchestrator).generate("Make toast")

        assertTrue(result.endsWith("Wrote BPMN to: toast.bpmn"), "result was:\n$result")
        assertFalse(result.contains("Preview"), "should not contain preview text: $result")
    }

    @Test
    fun `null render - passthrough as empty string, orchestrator not invoked`() {
        val shellCommands = mock(ShellCommands::class.java)
        val orchestrator = mock(BpmnPreviewOrchestrator::class.java)
        `when`(shellCommands.execute("Make toast", false, false, false, false, false, false, false, true, null))
            .thenReturn(null)

        val result = commandDelegatingTo(shellCommands, orchestrator).generate("Make toast")

        assertEquals("", result)
        // Verify runPreviewFlow was never called (no marker in null render)
        org.mockito.Mockito.verifyNoInteractions(orchestrator)
    }

    @Test
    fun `no marker - passthrough, orchestrator not invoked`() {
        val shellCommands = mock(ShellCommands::class.java)
        val orchestrator = mock(BpmnPreviewOrchestrator::class.java)
        `when`(shellCommands.execute("Make toast", false, false, false, false, false, false, false, true, null))
            .thenReturn("I'm sorry. I don't know how to proceed.")

        val result = commandDelegatingTo(shellCommands, orchestrator).generate("Make toast")

        assertEquals("I'm sorry. I don't know how to proceed.", result)
        org.mockito.Mockito.verifyNoInteractions(orchestrator)
    }

    @Test
    fun `orchestrator Opened - success text appended after Wrote BPMN`() {
        val shellCommands = mock(ShellCommands::class.java)
        val orchestrator = mock(BpmnPreviewOrchestrator::class.java)
        val previewPath = Paths.get("toast.preview.html")
        val rendered = renderedWithMarker("toast.bpmn")
        `when`(shellCommands.execute("Make toast", false, false, false, false, false, false, false, true, null))
            .thenReturn(rendered)
        `when`(orchestrator.runPreviewFlow("toast.bpmn"))
            .thenReturn(PreviewResult.Opened(previewPath))

        val result = commandDelegatingTo(shellCommands, orchestrator).generate("Make toast")

        assertTrue(result.contains("Wrote BPMN to:"), "should contain Wrote BPMN to: $result")
        assertTrue(result.contains("Preview opened in browser:"), "should contain success text: $result")
        assertTrue(result.contains(previewPath.toString()), "should contain preview path: $result")
    }

    @Test
    fun `orchestrator Fallback unsupported - fallback text includes preview path and reason`() {
        val shellCommands = mock(ShellCommands::class.java)
        val orchestrator = mock(BpmnPreviewOrchestrator::class.java)
        val previewPath = Paths.get("toast.preview.html")
        val rendered = renderedWithMarker("toast.bpmn")
        `when`(shellCommands.execute("Make toast", false, false, false, false, false, false, false, true, null))
            .thenReturn(rendered)
        `when`(orchestrator.runPreviewFlow("toast.bpmn"))
            .thenReturn(PreviewResult.Fallback(previewPath, "browser not supported: headless environment"))

        val result = commandDelegatingTo(shellCommands, orchestrator).generate("Make toast")

        assertTrue(result.contains("Wrote BPMN to:"), "should contain Wrote BPMN to: $result")
        assertTrue(result.contains(previewPath.toString()), "should contain preview path: $result")
        assertTrue(result.contains("headless environment"), "should contain outcome reason: $result")
        assertTrue(result.contains("browser not supported"), "should contain fallback label: $result")
    }

    @Test
    fun `orchestrator WriteFailed - write error text includes bpmn path and reason`() {
        val shellCommands = mock(ShellCommands::class.java)
        val orchestrator = mock(BpmnPreviewOrchestrator::class.java)
        val bpmnPath = Paths.get("toast.bpmn")
        val rendered = renderedWithMarker("toast.bpmn")
        `when`(shellCommands.execute("Make toast", false, false, false, false, false, false, false, true, null))
            .thenReturn(rendered)
        `when`(orchestrator.runPreviewFlow("toast.bpmn"))
            .thenReturn(PreviewResult.WriteFailed(bpmnPath, "Preview write failed: disk full"))

        val result = commandDelegatingTo(shellCommands, orchestrator).generate("Make toast")

        assertTrue(result.contains("Wrote BPMN to:"), "should contain Wrote BPMN to: $result")
        assertTrue(result.contains("Preview write failed"), "should contain write-failed label: $result")
        assertTrue(result.contains("disk full"), "should contain failure reason: $result")
        assertTrue(result.contains(bpmnPath.toString()), "should contain bpmn path: $result")
    }

    @Test
    fun `orchestrator Fallback failed - fallback text includes preview path and reason`() {
        val shellCommands = mock(ShellCommands::class.java)
        val orchestrator = mock(BpmnPreviewOrchestrator::class.java)
        val previewPath = Paths.get("toast.preview.html")
        val rendered = renderedWithMarker("toast.bpmn")
        `when`(shellCommands.execute("Make toast", false, false, false, false, false, false, false, true, null))
            .thenReturn(rendered)
        `when`(orchestrator.runPreviewFlow("toast.bpmn"))
            .thenReturn(PreviewResult.Fallback(previewPath, "Browser launch failed: process exit 1"))

        val result = commandDelegatingTo(shellCommands, orchestrator).generate("Make toast")

        assertTrue(result.contains("Wrote BPMN to:"), "should contain Wrote BPMN to: $result")
        assertTrue(result.contains(previewPath.toString()), "should contain preview path: $result")
        assertTrue(result.contains("process exit 1"), "should contain outcome reason: $result")
        assertTrue(result.contains("Browser launch failed"), "should contain fallback label: $result")
    }
}
