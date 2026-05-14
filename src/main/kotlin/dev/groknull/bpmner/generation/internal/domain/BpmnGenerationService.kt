package dev.groknull.bpmner.generation.internal.domain

import dev.groknull.bpmner.core.BpmnGenerationStatus
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.BpmnResult
import dev.groknull.bpmner.core.InputPathResolver
import dev.groknull.bpmner.core.ProcessInputAssessment
import dev.groknull.bpmner.core.ReadinessVerdict
import dev.groknull.bpmner.generation.BpmnGenerationInput
import dev.groknull.bpmner.generation.BpmnGenerationUseCase
import dev.groknull.bpmner.readiness.BpmnReadinessInvoker
import dev.groknull.bpmner.readiness.ReadinessReportWriter
import org.jmolecules.architecture.hexagonal.SecondaryPort
import org.jmolecules.ddd.annotation.Service
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@SecondaryPort
internal interface BpmnAgentInvoker {
    fun generate(request: BpmnRequest): BpmnResult
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
        val description = resolveProcessDescription(input)
        val outputFile = inputPathResolver.resolveOutputPath(input.outputFile).toString()
        val styleGuide =
            input.styleGuide?.let {
                logger.info("Loading style guide from file: {}", it)
                inputPathResolver.readUtf8(it).trim()
            }

        val request =
            BpmnRequest(
                processDescription = description,
                outputFile = outputFile,
                styleGuide = styleGuide,
            )

        logger.info(
            "Starting BPMN generation. outputFile={}, descriptionLength={}, styleGuidePresent={}, mode={}",
            outputFile,
            description.length,
            styleGuide != null,
            input.mode,
        )
        logger.debug("Process description:\n{}", description)
        if (styleGuide != null) logger.debug("Style guide:\n{}", styleGuide)

        val assessment = readinessInvoker.assess(request)
        logger.info(
            "Readiness assessment complete. verdict={}, overallScore={}",
            assessment.verdict,
            assessment.overallScore,
        )

        return when (assessment.verdict) {
            ReadinessVerdict.READY -> {
                val result = agentInvoker.generate(request)
                logger.info(
                    "BPMN generation completed. outputFile={}, xmlLength={}",
                    result.outputFile,
                    result.xml?.length ?: 0,
                )
                result
            }
            ReadinessVerdict.NEEDS_CLARIFICATION ->
                blockedResult(request, assessment, BpmnGenerationStatus.NEEDS_CLARIFICATION)
            ReadinessVerdict.NOT_A_PROCESS ->
                blockedResult(request, assessment, BpmnGenerationStatus.NOT_A_PROCESS)
        }
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
