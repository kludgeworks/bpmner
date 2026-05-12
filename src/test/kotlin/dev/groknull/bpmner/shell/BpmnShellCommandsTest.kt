package dev.groknull.bpmner.shell

import dev.groknull.bpmner.core.BpmnResult
import dev.groknull.bpmner.generation.BpmnGenerationInput
import dev.groknull.bpmner.generation.BpmnGenerationUseCase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BpmnShellCommandsTest {
    @Test
    fun `generate forwards inline process description`() {
        val generationUseCase = CapturingGenerationUseCase()
        val commands = BpmnShellCommands(generationUseCase)

        val response =
            commands.generate(
                processDescription = "Ship order",
                processFile = null,
                output = "ship.bpmn",
                styleGuide = "style.md",
            )

        assertEquals(
            BpmnGenerationInput(
                processDescription = "Ship order",
                outputFile = "ship.bpmn",
                styleGuide = "style.md",
            ),
            generationUseCase.calls.single(),
        )
        assertEquals("BPMN written to: ship.bpmn", response)
    }

    @Test
    fun `generate forwards process file`() {
        val generationUseCase = CapturingGenerationUseCase()
        val commands = BpmnShellCommands(generationUseCase)

        commands.generate(
            processDescription = null,
            processFile = "process.txt",
            output = "process.bpmn",
            styleGuide = null,
        )

        assertEquals(
            BpmnGenerationInput(
                processFile = "process.txt",
                outputFile = "process.bpmn",
            ),
            generationUseCase.calls.single(),
        )
    }

    private class CapturingGenerationUseCase : BpmnGenerationUseCase {
        val calls = mutableListOf<BpmnGenerationInput>()

        override fun generate(input: BpmnGenerationInput): BpmnResult {
            calls += input
            return BpmnResult(outputFile = input.outputFile, xml = "<definitions />")
        }
    }
}
