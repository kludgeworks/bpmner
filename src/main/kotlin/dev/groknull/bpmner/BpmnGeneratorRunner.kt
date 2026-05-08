package dev.groknull.bpmner

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.ansi.AnsiColor
import org.springframework.boot.ansi.AnsiOutput
import org.springframework.core.Ordered
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
    private val generationUseCase: BpmnGenerationUseCase,
    private val applicationShutdown: BpmnerApplicationShutdown,
) : ApplicationRunner, Ordered {

    private val logger = LoggerFactory.getLogger(BpmnGeneratorRunner::class.java)

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    override fun run(args: ApplicationArguments) {
        logger.debug("Runner invoked with option names: {}", args.optionNames)

        if (!args.containsOption("process") && !args.containsOption("process-file")) {
            logger.debug("No CLI process description provided; leaving startup to Spring Shell.")
            return
        }

        val result = generationUseCase.generate(
            BpmnGenerationInput(
                processDescription = args.getOptionValues("process")?.firstOrNull(),
                processFile = args.getOptionValues("process-file")?.firstOrNull(),
                outputFile = args.getOptionValues("output")?.firstOrNull() ?: "output.bpmn",
                styleGuide = args.getOptionValues("style-guide")?.firstOrNull(),
            )
        )
        println(AnsiOutput.toString(AnsiColor.BRIGHT_GREEN, "✨ Done! BPMN written to: ${result.outputFile}"))
        applicationShutdown.exit()
    }
}
