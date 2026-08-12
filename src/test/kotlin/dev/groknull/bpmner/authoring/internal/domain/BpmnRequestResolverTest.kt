/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring.internal.domain

import com.embabel.agent.domain.io.UserInput
import dev.groknull.bpmner.authoring.BpmnRequestDraft
import dev.groknull.bpmner.authoring.internal.adapter.inbound.InputPathResolver
import dev.groknull.bpmner.bpmn.GenerationMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Relocated from root `bpmner` package to `generation` (same-module) so that the import of
 * [InputPathResolver] is within the `generation` module's own internal boundary
 * (S5 — ARCHITECTURE §5 S5, §1.5).
 */
class BpmnRequestResolverTest {
    @Test
    fun `shell draft resolves inline prose with default output`(
        @TempDir tempDir: Path,
    ) {
        val resolver = resolver(tempDir)

        val request = resolver.resolveShellRequest(UserInput("Ship an order"), BpmnRequestDraft())

        assertEquals("Ship an order", request.processDescription)
        assertEquals(tempDir.resolve("output.bpmn").toString(), request.outputFile)
        assertEquals(GenerationMode.INTERACTIVE, request.mode)
    }

    @Test
    fun `shell draft resolves process file output file and style guide file`(
        @TempDir tempDir: Path,
    ) {
        tempDir.resolve("process.md").writeText("Approve invoice")
        tempDir.resolve("style.md").writeText("Use sentence case")
        val resolver = resolver(tempDir)

        val request =
            resolver.resolveShellRequest(
                UserInput("the workflow is in process.md"),
                BpmnRequestDraft(
                    processFile = "process.md",
                    outputFile = "invoice.bpmn",
                    styleGuideFile = "style.md",
                ),
            )

        assertEquals("Approve invoice", request.processDescription)
        assertEquals("Use sentence case", request.styleGuide)
        assertEquals(tempDir.resolve("invoice.bpmn").toString(), request.outputFile)
    }

    @Test
    fun `shell draft rejects blank process input`(
        @TempDir tempDir: Path,
    ) {
        val resolver = resolver(tempDir)

        assertThrows(IllegalArgumentException::class.java) {
            resolver.resolveShellRequest(UserInput("   "), BpmnRequestDraft())
        }
    }

    // The prose the pipeline sees is the prose the user typed. Quoting a task-kind hint used to
    // break the drafting model's JSON (#474); the draft no longer carries prose, so the hint
    // survives verbatim.
    @Test
    fun `inline prose reaches the request verbatim including quoted hints`(
        @TempDir tempDir: Path,
    ) {
        val typed = """Notify the baker [SEND messageName="no bread notification"] then stop."""

        val request = resolver(tempDir).resolveShellRequest(UserInput(typed), BpmnRequestDraft())

        assertEquals(typed, request.processDescription)
    }

    @Test
    fun `a process file wins over the instruction prose`(
        @TempDir tempDir: Path,
    ) {
        tempDir.resolve("process.md").writeText("Approve invoice")

        val request =
            resolver(tempDir).resolveShellRequest(
                UserInput("generate from process.md"),
                BpmnRequestDraft(processFile = "process.md"),
            )

        assertEquals("Approve invoice", request.processDescription)
    }

    private fun resolver(tempDir: Path) = BpmnRequestResolver(InputPathResolver(cwd = tempDir))
}
