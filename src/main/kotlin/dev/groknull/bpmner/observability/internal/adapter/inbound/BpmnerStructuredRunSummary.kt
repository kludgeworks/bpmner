package dev.groknull.bpmner.observability.internal.adapter.inbound

import com.embabel.agent.core.AgentProcess
import dev.groknull.bpmner.core.AutoFixedBpmnXml
import dev.groknull.bpmner.core.BpmnAutoFixResult
import dev.groknull.bpmner.core.BpmnDiagnostic
import dev.groknull.bpmner.core.BpmnDiagnosticSource
import dev.groknull.bpmner.core.BpmnGenerationStatus
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.BpmnResult
import dev.groknull.bpmner.core.FinalValidatedBpmnXml
import dev.groknull.bpmner.core.GlobalDiagnostics
import dev.groknull.bpmner.core.ProcessOutline
import dev.groknull.bpmner.core.RenderedBpmn
import dev.groknull.bpmner.core.ValidatedBpmnXml
import dev.groknull.bpmner.core.format
import java.time.Instant

data class BpmnerStructuredRunSummary(
    val schemaVersion: Int = 1,
    val runId: String,
    val timestamp: Instant,
    val status: String,
    val eventType: String,
    val durationMs: Long,
    val actions: List<BpmnerActionSummary>,
    val models: List<String>,
    val cost: Double,
    val usage: BpmnerUsageSummary,
    val request: BpmnerRequestSummary?,
    val artifacts: BpmnerArtifactSummary,
    val validation: BpmnerValidationRunSummary,
    val failure: String?,
)

data class BpmnerActionSummary(
    val name: String,
    val shortName: String,
    val timestamp: Instant,
    val durationMs: Long,
)

data class BpmnerUsageSummary(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
)

data class BpmnerRequestSummary(
    val processDescription: String,
    val styleGuidePresent: Boolean,
    val outputFile: String,
    val mode: String,
    val clarificationCount: Int,
)

data class BpmnerArtifactSummary(
    val processId: String?,
    val processName: String?,
    val outline: BpmnerOutlineSummary?,
    val renderedXmlLength: Int?,
    val validatedXmlLength: Int?,
    val finalXmlLength: Int?,
    val outputFile: String?,
    val generationStatus: String?,
    val autoFix: BpmnerAutoFixSummary?,
)

data class BpmnerOutlineSummary(
    val nodeCount: Int,
    val edgeCount: Int,
    val phaseCount: Int,
    val branchCount: Int,
    val loopCount: Int,
    val subprocessCount: Int,
)

data class BpmnerAutoFixSummary(
    val changed: Boolean,
    val applied: Int,
    val skipped: Int,
    val errors: Int,
)

data class BpmnerValidationRunSummary(
    val failedAttempts: List<BpmnerValidationAttemptSummary>,
    val passed: BpmnerValidationPassedSummary?,
)

data class BpmnerValidationAttemptSummary(
    val attemptNumber: Int,
    val repairAttempts: Int,
    val graphDiagnostics: Int,
    val renderDiagnostics: Int,
    val xsdDiagnostics: Int,
    val lintDiagnostics: Int,
    val topDiagnostics: List<String>,
)

data class BpmnerValidationPassedSummary(
    val repairAttempts: Int,
    val xmlLength: Int,
)

internal class BpmnerStructuredRunSummaryFactory {
    fun from(
        process: AgentProcess,
        eventType: String,
        validationEvents: BpmnerCollectedValidationEvents,
    ): BpmnerStructuredRunSummary {
        val usage = process.usage()
        val artifacts = process.blackboard.objects.toArtifacts()
        val autoFix = artifacts.autoFixed?.autoFixResult ?: artifacts.autoFixResult
        val definition = artifacts.rendered?.definition ?: artifacts.outline?.definition

        return BpmnerStructuredRunSummary(
            runId = process.id,
            timestamp = process.timestamp,
            status = process.status.name,
            eventType = eventType,
            durationMs = process.runningTime.toMillis(),
            actions =
                process.history.map { action ->
                    BpmnerActionSummary(
                        name = action.actionName,
                        shortName = action.actionName.substringAfterLast("."),
                        timestamp = action.timestamp,
                        durationMs = action.runningTime.toMillis(),
                    )
                },
            models = process.modelsUsed().map { it.name },
            cost = process.cost(),
            usage =
                BpmnerUsageSummary(
                    promptTokens = usage.promptTokens ?: 0,
                    completionTokens = usage.completionTokens ?: 0,
                    totalTokens = usage.totalTokens ?: 0,
                ),
            request = artifacts.request?.toSummary(),
            artifacts =
                BpmnerArtifactSummary(
                    processId = definition?.processId,
                    processName = definition?.processName,
                    outline = artifacts.outline?.toSummary(),
                    renderedXmlLength = artifacts.rendered?.xml?.length,
                    validatedXmlLength = artifacts.validated?.xml?.length,
                    finalXmlLength = artifacts.final?.xml?.length ?: artifacts.result?.xml?.length,
                    outputFile = artifacts.result?.outputFile ?: artifacts.request?.outputFile,
                    generationStatus =
                        artifacts.result?.status?.name
                            ?: BpmnGenerationStatus.GENERATED.name.takeIf { artifacts.final != null },
                    autoFix = autoFix?.toSummary(),
                ),
            validation = validationEvents.toSummary(),
            failure = process.failureInfo?.toString(),
        )
    }
}

private data class BlackboardArtifacts(
    val request: BpmnRequest? = null,
    val result: BpmnResult? = null,
    val outline: ProcessOutline? = null,
    val rendered: RenderedBpmn? = null,
    val validated: ValidatedBpmnXml? = null,
    val autoFixed: AutoFixedBpmnXml? = null,
    val final: FinalValidatedBpmnXml? = null,
    val autoFixResult: BpmnAutoFixResult? = null,
)

private fun List<Any>.toArtifacts(): BlackboardArtifacts {
    var artifacts = BlackboardArtifacts()
    forEach { obj ->
        artifacts =
            when (obj) {
                is BpmnRequest -> artifacts.copy(request = obj)
                is BpmnResult -> artifacts.copy(result = obj)
                is ProcessOutline -> artifacts.copy(outline = obj)
                is RenderedBpmn -> artifacts.copy(rendered = obj)
                is ValidatedBpmnXml -> artifacts.copy(validated = obj)
                is AutoFixedBpmnXml -> artifacts.copy(autoFixed = obj)
                is FinalValidatedBpmnXml -> artifacts.copy(final = obj)
                is BpmnAutoFixResult -> artifacts.copy(autoFixResult = obj)
                else -> artifacts
            }
    }
    return artifacts
}

private fun BpmnRequest.toSummary(): BpmnerRequestSummary =
    BpmnerRequestSummary(
        processDescription = processDescription,
        styleGuidePresent = styleGuide != null,
        outputFile = outputFile,
        mode = mode.name,
        clarificationCount = clarificationHistory.size,
    )

private fun ProcessOutline.toSummary(): BpmnerOutlineSummary =
    BpmnerOutlineSummary(
        nodeCount = definition.nodes.size,
        edgeCount = definition.sequences.size,
        phaseCount = metrics.phaseCount,
        branchCount = metrics.branchCount,
        loopCount = metrics.loopCount,
        subprocessCount = metrics.subprocessCount,
    )

private fun BpmnAutoFixResult.toSummary(): BpmnerAutoFixSummary =
    BpmnerAutoFixSummary(
        changed = changed,
        applied = applied.size,
        skipped = skipped.size,
        errors = errors.size,
    )

private fun BpmnerCollectedValidationEvents.toSummary(): BpmnerValidationRunSummary =
    BpmnerValidationRunSummary(
        failedAttempts =
            failed.map { event ->
                val global = GlobalDiagnostics(event.diagnostics)
                BpmnerValidationAttemptSummary(
                    attemptNumber = event.attemptNumber,
                    repairAttempts = event.repairAttempts,
                    graphDiagnostics = global.countFor(BpmnDiagnosticSource.GRAPH),
                    renderDiagnostics = global.countFor(BpmnDiagnosticSource.RENDER),
                    xsdDiagnostics = global.countFor(BpmnDiagnosticSource.XSD),
                    lintDiagnostics = global.countFor(BpmnDiagnosticSource.LINT),
                    topDiagnostics = event.diagnostics.take(MAX_TOP_DIAGNOSTICS).map(BpmnDiagnostic::format),
                )
            },
        passed =
            passed?.let { event ->
                BpmnerValidationPassedSummary(
                    repairAttempts = event.repairAttempts,
                    xmlLength = event.xml.length,
                )
            },
    )

private const val MAX_TOP_DIAGNOSTICS = 5
