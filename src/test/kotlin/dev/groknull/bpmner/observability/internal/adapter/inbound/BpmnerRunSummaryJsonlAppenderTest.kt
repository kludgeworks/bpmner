package dev.groknull.bpmner.observability.internal.adapter.inbound

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.groknull.bpmner.core.BpmnDiagnostic
import dev.groknull.bpmner.core.BpmnDiagnosticSource
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.validation.BpmnValidationFailedEvent
import dev.groknull.bpmner.validation.BpmnValidationPassedEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class BpmnerRunSummaryJsonlAppenderTest {
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()
    private val appender = BpmnerRunSummaryJsonlAppender(objectMapper)

    @Test
    fun `appends one compact json object per line`(
        @TempDir tempDir: Path,
    ) {
        appender.append(tempDir, sampleSummary("run-1"))
        appender.append(tempDir, sampleSummary("run-2"))

        val lines = Files.readAllLines(tempDir.resolve(BpmnerRunSummaryJsonlAppender.JSONL_FILE_NAME))

        assertEquals(2, lines.size)
        assertTrue(lines.all { it.isNotBlank() })
        assertFalse(lines.any { it.contains(System.lineSeparator()) })
        assertEquals("run-1", objectMapper.readTree(lines[0]).get("runId").asText())
        assertEquals("run-2", objectMapper.readTree(lines[1]).get("runId").asText())
    }

    @Test
    fun `does not throw when jsonl path cannot be written`(
        @TempDir tempDir: Path,
    ) {
        val fileInsteadOfDirectory = tempDir.resolve("not-a-directory")
        Files.writeString(fileInsteadOfDirectory, "already a file")

        appender.append(fileInsteadOfDirectory, sampleSummary("run-1"))

        assertEquals("already a file", Files.readString(fileInsteadOfDirectory))
    }

    @Test
    fun `collector groups validation events by request and removes them after read`() {
        val collector = BpmnerValidationEventCollector()
        val request = BpmnRequest("Approve order")
        val otherRequest = BpmnRequest("Ship order")

        collector.onValidationFailed(
            BpmnValidationFailedEvent(
                request = request,
                diagnostics =
                    listOf(
                        BpmnDiagnostic(
                            source = BpmnDiagnosticSource.LINT,
                            message = "Task name must use verb object",
                            rule = "klm/act-verb-object-name",
                        ),
                    ),
                attemptNumber = 1,
                repairAttempts = 0,
            ),
        )
        collector.onValidationPassed(
            BpmnValidationPassedEvent(
                request = request,
                xml = "<definitions />",
                repairAttempts = 1,
            ),
        )

        val collected = collector.removeFor(request)

        assertEquals(1, collected.failed.size)
        assertEquals(1, collected.passed?.repairAttempts)
        assertTrue(collector.removeFor(request).failed.isEmpty())
        assertTrue(collector.removeFor(otherRequest).failed.isEmpty())
    }

    private fun sampleSummary(runId: String): BpmnerStructuredRunSummary =
        BpmnerStructuredRunSummary(
            runId = runId,
            timestamp = Instant.parse("2026-05-15T08:00:00Z"),
            status = "COMPLETED",
            eventType = "AgentProcessCompletedEvent",
            durationMs = 125,
            actions =
                listOf(
                    BpmnerActionSummary(
                        name = "dev.groknull.bpmner.Agent.writeBpmn",
                        shortName = "writeBpmn",
                        timestamp = Instant.parse("2026-05-15T08:00:01Z"),
                        durationMs = 25,
                    ),
                ),
            models = listOf("gpt-4.1"),
            cost = 0.0123,
            usage = BpmnerUsageSummary(promptTokens = 10, completionTokens = 5, totalTokens = 15),
            request =
                BpmnerRequestSummary(
                    processDescription = "Approve order",
                    styleGuidePresent = false,
                    outputFile = "order.bpmn",
                    mode = "SINGLE_SHOT",
                    clarificationCount = 0,
                ),
            artifacts =
                BpmnerArtifactSummary(
                    processId = "Process_Order",
                    processName = "Order approval",
                    outline =
                        BpmnerOutlineSummary(
                            nodeCount = 3,
                            edgeCount = 2,
                            phaseCount = 1,
                            branchCount = 0,
                            loopCount = 0,
                            subprocessCount = 0,
                        ),
                    renderedXmlLength = 100,
                    validatedXmlLength = 100,
                    finalXmlLength = 120,
                    outputFile = "order.bpmn",
                    generationStatus = "GENERATED",
                    autoFix = null,
                ),
            validation =
                BpmnerValidationRunSummary(
                    failedAttempts = emptyList(),
                    passed = BpmnerValidationPassedSummary(repairAttempts = 0, xmlLength = 120),
                ),
            failure = null,
        )
}
