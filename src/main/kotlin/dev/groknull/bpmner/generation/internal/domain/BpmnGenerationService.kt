package dev.groknull.bpmner.generation.internal.domain
import dev.groknull.bpmner.guardrails.BpmnResult

import dev.groknull.bpmner.core.BpmnRequest



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
            "Starting BPMN generation. outputFile={}, descriptionLength={}, styleGuidePresent={}",
            outputFile,
            description.length,
            styleGuide != null,
        )
        logger.debug("Process description:\n{}", description)
        if (styleGuide != null) logger.debug("Style guide:\n{}", styleGuide)

        val result = agentInvoker.generate(request)
        logger.info(
            "BPMN generation completed. outputFile={}, xmlLength={}",
            result.outputFile,
            result.xml?.length ?: 0,
        )
        return result
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
