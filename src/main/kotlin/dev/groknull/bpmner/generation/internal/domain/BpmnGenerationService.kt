/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.domain

import dev.groknull.bpmner.alignment.BpmnAlignmentException
import dev.groknull.bpmner.core.AlignmentClassification
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.InputPathResolver
import dev.groknull.bpmner.generation.BpmnGenerationInput
import dev.groknull.bpmner.generation.BpmnGenerationStatus
import dev.groknull.bpmner.generation.BpmnGenerationUseCase
import dev.groknull.bpmner.generation.BpmnResult
import dev.groknull.bpmner.generation.StartGenerationOutcome
import dev.groknull.bpmner.readiness.BpmnReadinessInvoker
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessReportWriter
import dev.groknull.bpmner.readiness.ReadinessVerdict
import org.jmolecules.architecture.hexagonal.SecondaryPort
import org.jmolecules.ddd.annotation.Service
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@SecondaryPort
internal interface BpmnAgentInvoker {
    fun generate(
        request: BpmnRequest,
        assessment: ProcessInputAssessment,
    ): BpmnResult

    fun startAsync(
        request: BpmnRequest,
        assessment: ProcessInputAssessment,
    ): String
}

@Service
@Component
internal class BpmnGenerationService(
    private val agentInvoker: BpmnAgentInvoker,
    private val readinessInvoker: BpmnReadinessInvoker,
    private val readinessReportWriter: ReadinessReportWriter,
    private val inputPathResolver: InputPathResolver,
) : BpmnGenerationUseCase {
    private val logger = LoggerFactory.getLogger(BpmnGenerationService::class.java)

    override fun generate(input: BpmnGenerationInput): BpmnResult {
        val request = buildRequest(input)
        val assessment = assessReadiness(request)
        return when (assessment.verdict) {
            ReadinessVerdict.READY -> {
                performGeneration(request, assessment)
            }

            ReadinessVerdict.NEEDS_CLARIFICATION -> {
                blockedResult(request, assessment, BpmnGenerationStatus.NEEDS_CLARIFICATION)
            }
        }
    }

    override fun startAsync(input: BpmnGenerationInput): StartGenerationOutcome {
        val request = buildRequest(input)
        val assessment = assessReadiness(request)
        return when (assessment.verdict) {
            ReadinessVerdict.READY -> {
                StartGenerationOutcome.Started(agentInvoker.startAsync(request, assessment))
            }

            ReadinessVerdict.NEEDS_CLARIFICATION -> {
                StartGenerationOutcome.Blocked(
                    blockedResult(request, assessment, BpmnGenerationStatus.NEEDS_CLARIFICATION),
                )
            }
        }
    }

    private fun buildRequest(input: BpmnGenerationInput): BpmnRequest {
        val description = resolveProcessDescription(input)
        val outputFile = input.outputFile?.let { inputPathResolver.resolveOutputPath(it).toString() }
        val styleGuide = resolveStyleGuide(input)

        logGenerationStart(outputFile, input, description, styleGuide)

        return BpmnRequest(
            processDescription = description,
            outputFile = outputFile,
            styleGuide = styleGuide,
            mode = input.mode,
            clarificationHistory = input.clarificationHistory,
        )
    }

    private fun assessReadiness(request: BpmnRequest): ProcessInputAssessment {
        val assessment = readinessInvoker.assess(request)
        logger.info(
            "Readiness assessment complete. verdict={}, overallScore={}",
            assessment.verdict,
            assessment.overallScore,
        )
        return assessment
    }

    private fun resolveStyleGuide(input: BpmnGenerationInput): String? {
        if (input.styleGuideContent != null) {
            return input.styleGuideContent.trim().takeIf { it.isNotEmpty() }
        }
        return input.styleGuide?.let {
            logger.info("Loading style guide from file: {}", it)
            inputPathResolver.readUtf8(it).trim()
        }
    }

    private fun logGenerationStart(
        outputFile: String?,
        input: BpmnGenerationInput,
        description: String,
        styleGuide: String?,
    ) {
        logger.info(
            "Starting BPMN generation. outputFile={}, mode={}, descriptionLength={}, styleGuidePresent={}, clarifications={}",
            outputFile ?: "(none)",
            input.mode,
            description.length,
            styleGuide != null,
            input.clarificationHistory.size,
        )
        logger.debug("Process description:\n{}", description)
        if (styleGuide != null) logger.debug("Style guide:\n{}", styleGuide)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun performGeneration(
        request: BpmnRequest,
        assessment: ProcessInputAssessment,
    ): BpmnResult =
        try {
            val result = agentInvoker.generate(request, assessment)
            logger.info(
                "BPMN generation completed. outputFile={}, xmlLength={}",
                result.outputFile,
                result.xml?.length ?: 0,
            )
            result
        } catch (e: Exception) {
            handleGenerationException(e, request)
        }

    private fun handleGenerationException(
        e: Exception,
        request: BpmnRequest,
    ): BpmnResult {
        val alignmentEx = findAlignmentException(e)
        if (alignmentEx != null) {
            val report = alignmentEx.report
            val unsupportedCount =
                report.alignedElements.count { it.classification == AlignmentClassification.UNSUPPORTED }
            val missingCount =
                report.alignedElements.count { it.classification == AlignmentClassification.MISSING }
            val assumptionCount =
                report.alignedElements.count { it.classification == AlignmentClassification.ASSUMED }

            logger.warn(
                "BPMN generation blocked by semantic alignment guard. " +
                    "rationale={}, unsupported={}, missing={}, assumptions={}",
                report.rationale,
                unsupportedCount,
                missingCount,
                assumptionCount,
            )
            return BpmnResult(
                outputFile = request.outputFile,
                status = BpmnGenerationStatus.ALIGNMENT_FAILED,
                xml = null,
                alignmentReport = report,
            )
        }
        throw e
    }

    private fun findAlignmentException(e: Throwable): BpmnAlignmentException? {
        var current: Throwable? = e
        while (current != null) {
            if (current is BpmnAlignmentException) return current
            current = current.cause
        }
        return null
    }

    private fun blockedResult(
        request: BpmnRequest,
        assessment: ProcessInputAssessment,
        status: BpmnGenerationStatus,
    ): BpmnResult {
        val reportPath =
            readinessReportWriter.writeReport(
                originalInput = request.processDescription,
                assessment = assessment,
                outputFile = request.outputFile,
            )
        logger.warn(
            "BPMN generation blocked. verdict={}, status={}, reportFile={}",
            assessment.verdict,
            status,
            reportPath,
        )
        return BpmnResult(
            outputFile = request.outputFile,
            status = status,
            xml = null,
            readinessReport = assessment,
            reportFile = reportPath,
        )
    }

    private fun resolveProcessDescription(input: BpmnGenerationInput): String {
        val hasInlineDescription = !input.processDescription.isNullOrBlank()
        val hasProcessFile = !input.processFile.isNullOrBlank()
        require(hasInlineDescription != hasProcessFile) {
            "Provide exactly one of process description or --process-file"
        }

        if (hasInlineDescription) return input.processDescription!!.trim()

        logger.info("Loading process description from file: {}", input.processFile)
        return inputPathResolver.readUtf8(input.processFile!!).trim()
    }
}
