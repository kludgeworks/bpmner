package dev.groknull.bpmner

import com.embabel.agent.api.common.AgentPlatformTypedOps
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.ProcessOptions
import dev.groknull.bpmner.agent.BpmnRequest
import dev.groknull.bpmner.agent.BpmnResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

data class BpmnGenerationInput(
    val processDescription: String? = null,
    val processFile: String? = null,
    val outputFile: String = "output.bpmn",
    val styleGuide: String? = null,
)

interface BpmnGenerationUseCase {
    fun generate(input: BpmnGenerationInput): BpmnResult
}

internal interface BpmnAgentInvoker {
    fun generate(request: BpmnRequest): BpmnResult
}

@Component
internal class AgentPlatformBpmnAgentInvoker(
    private val agentPlatform: AgentPlatform,
) : BpmnAgentInvoker {
    override fun generate(request: BpmnRequest): BpmnResult =
        AgentPlatformTypedOps(agentPlatform)
            .transform<BpmnRequest, BpmnResult>(request, BpmnResult::class.java, ProcessOptions())
}

@Component
internal class BpmnGenerationService(
    private val agentInvoker: BpmnAgentInvoker,
    private val inputPathResolver: InputPathResolver = InputPathResolver(),
) : BpmnGenerationUseCase {

    private val logger = LoggerFactory.getLogger(BpmnGenerationService::class.java)

    override fun generate(input: BpmnGenerationInput): BpmnResult {
        val description = resolveProcessDescription(input)
        val outputFile = inputPathResolver.resolveOutputPath(input.outputFile).toString()
        val styleGuide = input.styleGuide?.let {
            logger.info("Loading style guide from file: {}", it)
            inputPathResolver.readUtf8(it).trim()
        }

        val request = BpmnRequest(
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
        if (styleGuide != null) {
            logger.debug("Style guide:\n{}", styleGuide)
        }

        val result = agentInvoker.generate(request)
        logger.info(
            "BPMN generation completed. outputFile={}, xmlLength={}",
            result.outputFile,
            result.xml.length,
        )
        return result
    }

    private fun resolveProcessDescription(input: BpmnGenerationInput): String {
        val hasInlineDescription = !input.processDescription.isNullOrBlank()
        val hasProcessFile = !input.processFile.isNullOrBlank()
        require(hasInlineDescription != hasProcessFile) {
            "Provide exactly one of process description or --process-file"
        }

        if (hasInlineDescription) {
            return input.processDescription!!.trim()
        }

        logger.info("Loading process description from file: {}", input.processFile)
        return inputPathResolver.readUtf8(input.processFile!!).trim()
    }
}
