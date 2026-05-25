/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.adapter.inbound

import dev.groknull.bpmner.BpmnerApplicationShutdown
import dev.groknull.bpmner.api.GenerationMode
import dev.groknull.bpmner.generation.BpmnGenerationInput
import dev.groknull.bpmner.generation.BpmnGenerationStatus
import dev.groknull.bpmner.generation.BpmnGenerationUseCase
import dev.groknull.bpmner.generation.BpmnResult
import dev.groknull.bpmner.generation.StartGenerationOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments

class BpmnGeneratorRunnerTest {
    @Test
    fun `no arguments do not trigger one shot generation`() {
        val generationUseCase = CapturingGenerationUseCase()
        val shutdown = CapturingApplicationShutdown()
        val runner = BpmnGeneratorRunner(generationUseCase, shutdown)

        runner.run(DefaultApplicationArguments())

        assertEquals(0, generationUseCase.calls.size)
        assertEquals(0, shutdown.calls)
    }

    @Test
    fun `process arguments are forwarded to generation use case`() {
        val generationUseCase = CapturingGenerationUseCase()
        val shutdown = CapturingApplicationShutdown()
        val runner = BpmnGeneratorRunner(generationUseCase, shutdown)

        runner.run(
            DefaultApplicationArguments(
                "--process=Ship order",
                "--output=ship.bpmn",
                "--style-guide=style.md",
            ),
        )

        assertEquals(
            BpmnGenerationInput(
                processDescription = "Ship order",
                outputFile = "ship.bpmn",
                styleGuide = "style.md",
                mode = GenerationMode.SINGLE_SHOT,
            ),
            generationUseCase.calls.single(),
        )
        assertEquals(1, shutdown.calls)
    }

    @Test
    fun `prints needs-clarification message with readiness report path`() {
        val message =
            messageOnResult(
                BpmnResult(
                    outputFile = null,
                    status = BpmnGenerationStatus.NEEDS_CLARIFICATION,
                    xml = null,
                    reportFile = "/tmp/clar.readiness.md",
                ),
            )

        assertTrue(message.contains("needs clarification"), "expected clarification phrasing, got: $message")
        assertTrue(message.contains("/tmp/clar.readiness.md"), "expected report path in message, got: $message")
    }

    @Test
    fun `prints clarification message even for inputs without workflow signal`() {
        val message =
            messageOnResult(
                BpmnResult(
                    outputFile = null,
                    status = BpmnGenerationStatus.NEEDS_CLARIFICATION,
                    xml = null,
                    reportFile = "/tmp/no-workflow.readiness.md",
                ),
            )

        assertTrue(message.contains("needs clarification"), "expected clarification phrasing, got: $message")
        assertTrue(message.contains("/tmp/no-workflow.readiness.md"), "expected report path, got: $message")
    }

    @Test
    fun `prints fallback when no readiness report path was written`() {
        val message =
            messageOnResult(
                BpmnResult(
                    outputFile = null,
                    status = BpmnGenerationStatus.NEEDS_CLARIFICATION,
                    xml = null,
                    reportFile = null,
                ),
            )

        assertTrue(message.contains("(not written)"), "expected (not written) marker, got: $message")
    }

    @Test
    fun `prints alignment-failed message`() {
        val message =
            messageOnResult(
                BpmnResult(
                    outputFile = null,
                    status = BpmnGenerationStatus.ALIGNMENT_FAILED,
                    xml = null,
                ),
            )

        assertTrue(message.contains("ALIGNMENT_FAILED"), "expected status name in message, got: $message")
    }

    @Test
    fun `prints validation-failed message`() {
        val message =
            messageOnResult(
                BpmnResult(
                    outputFile = null,
                    status = BpmnGenerationStatus.VALIDATION_FAILED,
                    xml = null,
                ),
            )

        assertTrue(message.contains("VALIDATION_FAILED"), "expected status name in message, got: $message")
    }

    private fun messageOnResult(result: BpmnResult): String {
        val runner = BpmnGeneratorRunner(CapturingGenerationUseCase(result), CapturingApplicationShutdown())
        val bytes = java.io.ByteArrayOutputStream()
        val originalOut = System.out
        System.setOut(java.io.PrintStream(bytes, true, Charsets.UTF_8))
        try {
            runner.run(DefaultApplicationArguments("--process=anything"))
        } finally {
            System.setOut(originalOut)
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private class CapturingGenerationUseCase(
        private val nextResult: BpmnResult? = null,
    ) : BpmnGenerationUseCase {
        val calls = mutableListOf<BpmnGenerationInput>()

        override fun generate(input: BpmnGenerationInput): BpmnResult {
            calls += input
            return nextResult ?: BpmnResult(
                outputFile = input.outputFile,
                status = BpmnGenerationStatus.GENERATED,
                xml = "<definitions />",
            )
        }

        override fun startAsync(input: BpmnGenerationInput): StartGenerationOutcome {
            error("BpmnGeneratorRunner should not call startAsync")
        }
    }

    private class CapturingApplicationShutdown : BpmnerApplicationShutdown {
        var calls = 0

        override fun exit() {
            calls += 1
        }
    }
}
