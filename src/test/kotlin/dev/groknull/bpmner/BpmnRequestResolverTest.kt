/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner

import dev.groknull.bpmner.api.GenerationMode
import dev.groknull.bpmner.generation.BpmnRequestDraft
import dev.groknull.bpmner.generation.BpmnRequestResolver
import dev.groknull.bpmner.generation.internal.adapter.inbound.InputPathResolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class BpmnRequestResolverTest {
    @Test
    fun `shell draft resolves inline prose with default output`(
        @TempDir tempDir: Path,
    ) {
        val resolver = resolver(tempDir)

        val request = resolver.resolveShellRequest(BpmnRequestDraft(processDescription = "Ship an order"))

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
    fun `shell draft rejects missing or ambiguous process input`(
        @TempDir tempDir: Path,
    ) {
        val resolver = resolver(tempDir)

        assertThrows(IllegalArgumentException::class.java) {
            resolver.resolveShellRequest(BpmnRequestDraft())
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolver.resolveShellRequest(
                BpmnRequestDraft(
                    processDescription = "Inline",
                    processFile = "process.md",
                ),
            )
        }
    }

    private fun resolver(tempDir: Path) = BpmnRequestResolver(InputPathResolver(cwd = tempDir))
}
