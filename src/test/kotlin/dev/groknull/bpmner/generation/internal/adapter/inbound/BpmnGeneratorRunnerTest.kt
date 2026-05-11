package dev.groknull.bpmner.generation.internal.adapter.inbound

import dev.groknull.bpmner.BpmnerApplicationShutdown
import dev.groknull.bpmner.core.BpmnResult
import dev.groknull.bpmner.generation.BpmnGenerationInput
import dev.groknull.bpmner.generation.BpmnGenerationUseCase
import org.junit.jupiter.api.Assertions.assertEquals
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
            )
        )

        assertEquals(
            BpmnGenerationInput(
                processDescription = "Ship order",
                outputFile = "ship.bpmn",
                styleGuide = "style.md",
            ),
            generationUseCase.calls.single(),
        )
        assertEquals(1, shutdown.calls)
    }

    private class CapturingGenerationUseCase : BpmnGenerationUseCase {
        val calls = mutableListOf<BpmnGenerationInput>()

        override fun generate(input: BpmnGenerationInput): BpmnResult {
            calls += input
            return BpmnResult(outputFile = input.outputFile, xml = "<definitions />")
        }
    }

    private class CapturingApplicationShutdown : BpmnerApplicationShutdown {
        var calls = 0

        override fun exit() {
            calls += 1
        }
    }
}
