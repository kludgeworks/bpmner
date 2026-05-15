package dev.groknull.bpmner.generation.internal.domain

import dev.groknull.bpmner.alignment.AlignmentVerdict
import dev.groknull.bpmner.alignment.BpmnAlignmentException
import dev.groknull.bpmner.alignment.BpmnAlignmentReport
import dev.groknull.bpmner.alignment.BpmnDefinitionSummary
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.ClarificationExchange
import dev.groknull.bpmner.core.GenerationMode
import dev.groknull.bpmner.core.InputPathResolver
import dev.groknull.bpmner.core.ReadinessDimension
import dev.groknull.bpmner.generation.BpmnGenerationInput
import dev.groknull.bpmner.generation.BpmnGenerationStatus
import dev.groknull.bpmner.generation.BpmnResult
import dev.groknull.bpmner.readiness.BpmnReadinessInvoker
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessDimensionScore
import dev.groknull.bpmner.readiness.ReadinessReportWriter
import dev.groknull.bpmner.readiness.ReadinessVerdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
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
        val readiness = StubReadinessInvoker(assessment(ReadinessVerdict.READY, 92))
        val reportWriter = CapturingReportWriter()
        val service = service(invoker, readiness, reportWriter)

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
        assertEquals(BpmnGenerationStatus.GENERATED, result.status)
        assertNull(result.reportFile)
        assertTrue(reportWriter.calls.isEmpty())
    }

    @Test
    fun `preserves mode and clarification history on request`() {
        val invoker = CapturingBpmnAgentInvoker()
        val readiness = StubReadinessInvoker(assessment(ReadinessVerdict.READY, 90))
        val service = service(invoker, readiness, CapturingReportWriter())

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
        val readiness = StubReadinessInvoker(assessment(ReadinessVerdict.READY, 90))
        val service = service(invoker, readiness, CapturingReportWriter())

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
            service(
                CapturingBpmnAgentInvoker(),
                StubReadinessInvoker(assessment(ReadinessVerdict.READY, 90)),
                CapturingReportWriter(),
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

    @Test
    fun `blocks generation and writes report when readiness needs clarification`() {
        val invoker = CapturingBpmnAgentInvoker()
        val assessment = assessment(ReadinessVerdict.NEEDS_CLARIFICATION, 55)
        val readiness = StubReadinessInvoker(assessment)
        val reportWriter = CapturingReportWriter(reportPath = tempDir.resolve("weak.bpmn.readiness.md").toString())
        val service = service(invoker, readiness, reportWriter)

        val result =
            service.generate(
                BpmnGenerationInput(
                    processDescription = "Make it nicer for users",
                    outputFile = "weak.bpmn",
                ),
            )

        assertEquals(BpmnGenerationStatus.NEEDS_CLARIFICATION, result.status)
        assertNull(result.xml)
        assertNotNull(result.readinessReport)
        assertEquals(tempDir.resolve("weak.bpmn.readiness.md").toString(), result.reportFile)
        assertTrue(invoker.calls.isEmpty(), "generator must not run when readiness blocks generation")
        val writerCall = reportWriter.calls.single()
        assertEquals("Make it nicer for users", writerCall.originalInput)
        assertEquals(tempDir.resolve("weak.bpmn").toString(), writerCall.outputFile)
        assertEquals(assessment, writerCall.assessment)
    }

    @Test
    fun `blocks generation and writes report when input is not a process`() {
        val invoker = CapturingBpmnAgentInvoker()
        val assessment = assessment(ReadinessVerdict.NOT_A_PROCESS, 20)
        val readiness = StubReadinessInvoker(assessment)
        val reportWriter = CapturingReportWriter(reportPath = tempDir.resolve("haiku.bpmn.readiness.md").toString())
        val service = service(invoker, readiness, reportWriter)

        val result =
            service.generate(
                BpmnGenerationInput(
                    processDescription = "Cherry blossoms drift on the spring breeze",
                    outputFile = "haiku.bpmn",
                ),
            )

        assertEquals(BpmnGenerationStatus.NOT_A_PROCESS, result.status)
        assertNull(result.xml)
        assertNotNull(result.readinessReport)
        assertEquals(tempDir.resolve("haiku.bpmn.readiness.md").toString(), result.reportFile)
        assertTrue(invoker.calls.isEmpty(), "generator must not run when readiness blocks generation")
    }

    @Test
    fun `blocks generation when alignment failure occurs`() {
        val invoker = AlignmentFailingBpmnAgentInvoker()
        val readiness = StubReadinessInvoker(assessment(ReadinessVerdict.READY, 95))
        val service = service(invoker, readiness, CapturingReportWriter())

        val result =
            service.generate(
                BpmnGenerationInput(
                    processDescription = "Ship order",
                    outputFile = "order.bpmn",
                ),
            )

        assertEquals(BpmnGenerationStatus.ALIGNMENT_FAILED, result.status)
        assertNull(result.xml)
        assertNotNull(result.alignmentReport)
        assertEquals(AlignmentVerdict.FAILED, result.alignmentReport?.verdict)
    }

    private fun service(
        invoker: BpmnAgentInvoker,
        readinessInvoker: BpmnReadinessInvoker,
        reportWriter: ReadinessReportWriter,
    ) = BpmnGenerationService(
        agentInvoker = invoker,
        readinessInvoker = readinessInvoker,
        readinessReportWriter = reportWriter,
        inputPathResolver = InputPathResolver(cwd = tempDir),
    )

    private fun assessment(
        verdict: ReadinessVerdict,
        score: Int,
    ): ProcessInputAssessment =
        ProcessInputAssessment(
            verdict = verdict,
            overallScore = score,
            dimensions =
                ReadinessDimension.entries.map {
                    ReadinessDimensionScore(
                        dimension = it,
                        score = score,
                        rationale = "Stubbed dimension score.",
                    )
                },
            rationale = "Stubbed rationale.",
        )

    private class CapturingBpmnAgentInvoker : BpmnAgentInvoker {
        val calls = mutableListOf<BpmnRequest>()

        val lastRequest: BpmnRequest
            get() = calls.last()

        override fun generate(request: BpmnRequest): BpmnResult {
            calls += request
            return BpmnResult(
                outputFile = request.outputFile,
                status = BpmnGenerationStatus.GENERATED,
                xml = "<definitions />",
            )
        }
    }

    private class AlignmentFailingBpmnAgentInvoker : BpmnAgentInvoker {
        override fun generate(request: BpmnRequest): BpmnResult =
            throw BpmnAlignmentException(
                message = "Alignment failed.",
                report =
                    BpmnAlignmentReport(
                        verdict = AlignmentVerdict.FAILED,
                        rationale = "Hallucinated tasks found.",
                        bpmnSummary = BpmnDefinitionSummary("P1", "Order", emptyList()),
                    ),
            )
    }

    private class StubReadinessInvoker(
        private val assessment: ProcessInputAssessment,
    ) : BpmnReadinessInvoker {
        override fun assess(request: BpmnRequest): ProcessInputAssessment = assessment
    }

    private data class ReportWriterCall(
        val originalInput: String,
        val assessment: ProcessInputAssessment,
        val outputFile: String,
    )

    private class CapturingReportWriter(
        private val reportPath: String = "/tmp/report.md",
    ) : ReadinessReportWriter {
        val calls = mutableListOf<ReportWriterCall>()

        override fun writeReport(
            originalInput: String,
            assessment: ProcessInputAssessment,
            outputFile: String,
        ): String {
            calls += ReportWriterCall(originalInput, assessment, outputFile)
            return reportPath
        }
    }
}
