package dev.groknull.bpmner

import com.embabel.agent.api.common.AgentPlatformTypedOps
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.ProcessOptions
import dev.groknull.bpmner.agent.BpmnRequest
import dev.groknull.bpmner.agent.BpmnResult
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.ansi.AnsiColor
import org.springframework.boot.ansi.AnsiOutput
import org.springframework.stereotype.Component

/**
 * CLI entry point. Pass the process description as a command-line argument:
 *
 *   --process="Order goes from customer to warehouse to shipping"
 *   --process-file=process.txt
 *   --output=order-fulfillment.bpmn          (optional, default: output.bpmn)
 *   --style-guide=style.md                   (optional)
 */
@Component
class BpmnGeneratorRunner(
    private val agentPlatform: AgentPlatform,
) : ApplicationRunner {

    private val logger = LoggerFactory.getLogger(BpmnGeneratorRunner::class.java)
    private val inputPathResolver = InputPathResolver()

    override fun run(args: ApplicationArguments) {
        logger.debug("Runner invoked with option names: {}", args.optionNames)

        val description = args.getOptionValues("process")?.firstOrNull()
            ?: args.getOptionValues("process-file")?.firstOrNull()
                ?.let {
                    logger.info("Loading process description from file: {}", it)
                    inputPathResolver.readUtf8(it).trim()
                }
            ?: run {
                logger.warn("No process description provided — skipping BPMN generation.")
                logger.warn("Usage: --process='<description>' or --process-file=<file.txt>")
                return
            }

        val outputFileRaw = args.getOptionValues("output")?.firstOrNull() ?: "output.bpmn"
        val outputFile = inputPathResolver.resolveOutputPath(outputFileRaw).toString()
        val styleGuide = args.getOptionValues("style-guide")?.firstOrNull()
            ?.let {
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

        val result = AgentPlatformTypedOps(agentPlatform)
            .transform<BpmnRequest, BpmnResult>(request, BpmnResult::class.java, ProcessOptions())

        logger.info(
            "BPMN generation completed. outputFile={}, xmlLength={}",
            result.outputFile,
            result.xml.length,
        )
        println(AnsiOutput.toString(AnsiColor.BRIGHT_GREEN, "✨ Done! BPMN written to: ${result.outputFile}"))
    }
}
