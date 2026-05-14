package dev.groknull.bpmner.generation.internal.domain

import dev.groknull.bpmner.core.BpmnGenerationStatus
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.BpmnResult
import dev.groknull.bpmner.core.ClarificationExchange
import dev.groknull.bpmner.core.GenerationMode
import dev.groknull.bpmner.core.InputPathResolver
import dev.groknull.bpmner.generation.BpmnGenerationInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class BpmnGenerationServiceTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `generates from inline process description and resolves output path`() {
        val invoker = CapturingBpmnAgentInvoker()
        val service =
            BpmnGenerationService(
                agentInvoker = invoker,
                inputPathResolver = InputPathResolver(cwd = tempDir),
            )

        val result =
            service.generate(
                BpmnGenerationInput(
                    processDescription = "  Order is packed and shipped  ",
                    outputFile = "order.bpmn",
                ),
            )

        assertEquals(tempDir.resolve("order.bpmn").toString(), invoker.lastRequest.outputFile)
        assertEquals("Order is packed and shipped", invoker.lastRequest.processDescription)
        assertEquals(tempDir.resolve("order.bpmn").toString(), result.outputFile)
    }

    @Test
    fun `preserves mode and clarification history on request`() {
        val invoker = CapturingBpmnAgentInvoker()
        val service =
            BpmnGenerationService(
                agentInvoker = invoker,
                inputPathResolver = InputPathResolver(cwd = tempDir),
            )
        val clarification =
            ClarificationExchange(
                questionId = "q1",
                questionText = "What starts the process?",
                answerText = "The customer submits an order.",
            )

        service.generate(
            BpmnGenerationInput(
                processDescription = "Ship order",
                outputFile = "order.bpmn",
                mode = GenerationMode.INTERACTIVE,
                clarificationHistory = listOf(clarification),
            ),
        )

        assertEquals(GenerationMode.INTERACTIVE, invoker.lastRequest.mode)
        assertEquals(listOf(clarification), invoker.lastRequest.clarificationHistory)
    }

    @Test
    fun `generates from process file and style guide file`() {
        val processFile = tempDir.resolve("process.txt")
        val styleGuideFile = tempDir.resolve("style.md")
        processFile.writeText("  Approve invoice and pay supplier  ")
        styleGuideFile.writeText("  Use verb object task names  ")
        val invoker = CapturingBpmnAgentInvoker()
        val service =
            BpmnGenerationService(
                agentInvoker = invoker,
                inputPathResolver = InputPathResolver(cwd = tempDir),
            )

        service.generate(
            BpmnGenerationInput(
                processFile = "process.txt",
                outputFile = "invoice.bpmn",
                styleGuide = "style.md",
            ),
        )

        assertEquals("Approve invoice and pay supplier", invoker.lastRequest.processDescription)
        assertEquals("Use verb object task names", invoker.lastRequest.styleGuide)
        assertEquals(tempDir.resolve("invoice.bpmn").toString(), invoker.lastRequest.outputFile)
    }

    @Test
    fun `requires exactly one process source`() {
        val service =
            BpmnGenerationService(
                agentInvoker = CapturingBpmnAgentInvoker(),
                inputPathResolver = InputPathResolver(cwd = tempDir),
            )

        assertThrows(IllegalArgumentException::class.java) {
            service.generate(BpmnGenerationInput())
        }
        assertThrows(IllegalArgumentException::class.java) {
            service.generate(
                BpmnGenerationInput(
                    processDescription = "Order is shipped",
                    processFile = "process.txt",
                ),
            )
        }
    }

    private class CapturingBpmnAgentInvoker : BpmnAgentInvoker {
        lateinit var lastRequest: BpmnRequest

        override fun generate(request: BpmnRequest): BpmnResult {
            lastRequest = request
            return BpmnResult(
                outputFile = request.outputFile,
                status = BpmnGenerationStatus.GENERATED,
                xml = "<definitions />",
            )
        }
    }
}
