package dev.groknull.bpmner.generation.internal.adapter.inbound

import dev.groknull.bpmner.BpmnerApplicationShutdown
import dev.groknull.bpmner.core.BpmnGenerationStatus
import dev.groknull.bpmner.core.BpmnResult
import dev.groknull.bpmner.core.GenerationMode
import dev.groknull.bpmner.generation.BpmnGenerationInput
import dev.groknull.bpmner.generation.BpmnGenerationUseCase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class BpmnGeneratorRunnerTest {
    @Test
    fun `no arguments do not trigger one shot generation`() {
        val generationUseCase = StubGenerationUseCase()
        val shutdown = CapturingApplicationShutdown()
        val runner = BpmnGeneratorRunner(generationUseCase, shutdown)

        runner.run(DefaultApplicationArguments())

        assertEquals(0, generationUseCase.calls.size)
        assertEquals(0, shutdown.calls)
    }

    @Test
    fun `process arguments are forwarded to generation use case with single shot mode`() {
        val generationUseCase = StubGenerationUseCase()
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
    fun `prints success message when generated`() {
        val output = runAndCaptureStdout(StubGenerationUseCase(BpmnGenerationStatus.GENERATED))
        assertTrue(output.contains("Done!"))
        assertTrue(output.contains("ship.bpmn"))
    }

    @Test
    fun `prints blocked message and report path for needs clarification`() {
        val output =
            runAndCaptureStdout(
                StubGenerationUseCase(
                    BpmnGenerationStatus.NEEDS_CLARIFICATION,
                    reportFile = "/tmp/ship.bpmn.readiness.md",
                ),
            )
        assertTrue(output.contains("Generation blocked"))
        assertTrue(output.contains("needs clarification"))
        assertTrue(output.contains("/tmp/ship.bpmn.readiness.md"))
        assertFalse(output.contains("Done!"))
    }

    @Test
    fun `prints blocked message and report path for not a process`() {
        val output =
            runAndCaptureStdout(
                StubGenerationUseCase(
                    BpmnGenerationStatus.NOT_A_PROCESS,
                    reportFile = "/tmp/ship.bpmn.readiness.md",
                ),
            )
        assertTrue(output.contains("Generation blocked"))
        assertTrue(output.contains("not a process"))
        assertTrue(output.contains("/tmp/ship.bpmn.readiness.md"))
    }

    private fun runAndCaptureStdout(useCase: StubGenerationUseCase): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        return try {
            System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
            BpmnGeneratorRunner(useCase, CapturingApplicationShutdown())
                .run(DefaultApplicationArguments("--process=Ship order", "--output=ship.bpmn"))
            buffer.toString(Charsets.UTF_8)
        } finally {
            System.setOut(original)
        }
    }

    private class StubGenerationUseCase(
        private val status: BpmnGenerationStatus = BpmnGenerationStatus.GENERATED,
        private val reportFile: String? = null,
    ) : BpmnGenerationUseCase {
        val calls = mutableListOf<BpmnGenerationInput>()

        override fun generate(input: BpmnGenerationInput): BpmnResult {
            calls += input
            return BpmnResult(
                outputFile = input.outputFile,
                status = status,
                xml = if (status == BpmnGenerationStatus.GENERATED) "<definitions />" else null,
                reportFile = reportFile,
            )
        }
    }

    private class CapturingApplicationShutdown : BpmnerApplicationShutdown {
        var calls = 0

        override fun exit() {
            calls += 1
        }
    }
}
