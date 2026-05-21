/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.adapter.inbound

import dev.groknull.bpmner.BpmnerApplicationShutdown
import dev.groknull.bpmner.core.GenerationMode
import dev.groknull.bpmner.generation.BpmnGenerationInput
import dev.groknull.bpmner.generation.BpmnGenerationStatus
import dev.groknull.bpmner.generation.BpmnGenerationUseCase
import dev.groknull.bpmner.generation.BpmnResult
import org.jmolecules.architecture.hexagonal.PrimaryAdapter
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.ansi.AnsiColor
import org.springframework.boot.ansi.AnsiOutput
import org.springframework.core.Ordered
import org.springframework.stereotype.Component

@PrimaryAdapter
@Component
class BpmnGeneratorRunner(
    private val generationUseCase: BpmnGenerationUseCase,
    private val applicationShutdown: BpmnerApplicationShutdown,
) : ApplicationRunner,
    Ordered {
    private val logger = LoggerFactory.getLogger(BpmnGeneratorRunner::class.java)

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    override fun run(args: ApplicationArguments) {
        logger.debug("Runner invoked with option names: {}", args.optionNames)

        if (!args.containsOption("process") && !args.containsOption("process-file")) {
            logger.debug("No CLI process description provided; leaving startup to Spring Shell.")
            return
        }

        val result =
            generationUseCase.generate(
                BpmnGenerationInput(
                    processDescription = args.getOptionValues("process")?.firstOrNull(),
                    processFile = args.getOptionValues("process-file")?.firstOrNull(),
                    outputFile = args.getOptionValues("output")?.firstOrNull() ?: "output.bpmn",
                    styleGuide = args.getOptionValues("style-guide")?.firstOrNull(),
                    mode = GenerationMode.SINGLE_SHOT,
                ),
            )
        println(messageFor(result))
        applicationShutdown.exit()
    }

    private fun messageFor(result: BpmnResult): String = when (result.status) {
        BpmnGenerationStatus.GENERATED -> {
            AnsiOutput.toString(
                AnsiColor.BRIGHT_GREEN,
                "✨ Done! BPMN written to: ${result.outputFile ?: "(none)"}",
            )
        }

        BpmnGenerationStatus.NEEDS_CLARIFICATION -> {
            AnsiOutput.toString(
                AnsiColor.BRIGHT_YELLOW,
                "⚠ Generation blocked: input needs clarification. Readiness report: ${result.reportFile ?: "(not written)"}",
            )
        }

        BpmnGenerationStatus.ALIGNMENT_FAILED,
        BpmnGenerationStatus.VALIDATION_FAILED,
        -> {
            AnsiOutput.toString(
                AnsiColor.BRIGHT_RED,
                "⚠ Generation finished with status ${result.status}.",
            )
        }
    }
}
