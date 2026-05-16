/*
 * Copyright (c) 2026 The Project Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package dev.groknull.bpmner.generation.internal.adapter.inbound

import dev.groknull.bpmner.BpmnerApplicationShutdown
import dev.groknull.bpmner.core.GenerationMode
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
    fun `prints not-a-process message with readiness report path`() {
        val message =
            messageOnResult(
                BpmnResult(
                    outputFile = null,
                    status = BpmnGenerationStatus.NOT_A_PROCESS,
                    xml = null,
                    reportFile = "/tmp/not-a-process.readiness.md",
                ),
            )

        assertTrue(message.contains("not a process"), "expected not-a-process phrasing, got: $message")
        assertTrue(message.contains("/tmp/not-a-process.readiness.md"), "expected report path, got: $message")
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
