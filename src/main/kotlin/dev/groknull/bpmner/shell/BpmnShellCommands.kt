package dev.groknull.bpmner.shell

import dev.groknull.bpmner.core.BpmnGenerationStatus
import dev.groknull.bpmner.core.BpmnResult
import dev.groknull.bpmner.generation.BpmnGenerationInput
import dev.groknull.bpmner.generation.BpmnGenerationUseCase
import org.jmolecules.architecture.hexagonal.PrimaryAdapter
import org.springframework.shell.standard.ShellComponent
import org.springframework.shell.standard.ShellMethod
import org.springframework.shell.standard.ShellOption

@PrimaryAdapter
@ShellComponent
class BpmnShellCommands(
    private val generationUseCase: BpmnGenerationUseCase,
) {
    @ShellMethod(
        value = "Generate a BPMN 2.0 diagram from a process description",
        key = ["generate", "gen"],
    )
    fun generate(
        @ShellOption(
            value = ["--process"],
            help = "Natural-language process description",
            defaultValue = ShellOption.NULL,
        ) processDescription: String?,
        @ShellOption(
            value = ["--process-file"],
            help = "Path to a file containing the process description",
            defaultValue = ShellOption.NULL,
        ) processFile: String?,
        @ShellOption(
            value = ["--output"],
            help = "BPMN output path",
            defaultValue = "output.bpmn",
        ) output: String,
        @ShellOption(
            value = ["--style-guide"],
            help = "Optional Markdown style guide path",
            defaultValue = ShellOption.NULL,
        ) styleGuide: String?,
    ): String {
        val result =
            generationUseCase.generate(
                BpmnGenerationInput(
                    processDescription = processDescription,
                    processFile = processFile,
                    outputFile = output,
                    styleGuide = styleGuide,
                ),
            )
        return messageFor(result)
    }

    private fun messageFor(result: BpmnResult): String =
        when (result.status) {
            BpmnGenerationStatus.GENERATED -> "BPMN written to: ${result.outputFile}"
            BpmnGenerationStatus.NEEDS_CLARIFICATION ->
                "Generation blocked: input needs clarification. Readiness report: ${result.reportFile ?: "(not written)"}"
            BpmnGenerationStatus.NOT_A_PROCESS ->
                "Generation blocked: input is not a process. Readiness report: ${result.reportFile ?: "(not written)"}"
            BpmnGenerationStatus.ALIGNMENT_FAILED,
            BpmnGenerationStatus.VALIDATION_FAILED,
            -> "Generation finished with status ${result.status}."
        }
}
