/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.pipeline.internal.adapter.inbound

import com.embabel.agent.shell.ShellCommands
import dev.groknull.bpmner.authoring.GENERATED_CONTENT_PREFIX
import org.jmolecules.architecture.hexagonal.PrimaryAdapter
import org.springframework.beans.factory.ObjectProvider
import org.springframework.shell.standard.ShellComponent
import org.springframework.shell.standard.ShellMethod
import org.springframework.shell.standard.ShellOption

/**
 * Dedicated shell entrypoint that runs the BPMN pipeline without the `-o` flag.
 *
 * Embabel's built-in `x`/`execute` defaults to closed mode. Now that the pipeline
 * is orchestrated by a single `BpmnGenerationAgent`, closed mode works correctly and
 * we do not need to force open mode. This command delegates to embabel's `execute`
 * in closed mode, reusing its goal composition, interactive clarification (HITL) loop,
 * and result formatting — so `generate "<description>"` just works.
 *
 * [ShellCommands] is resolved through an [ObjectProvider] so this bean instantiates even in contexts
 * where the embabel shell is not active (e.g. non-shell `@SpringBootTest` contexts); the provider is
 * only dereferenced when the command actually runs, which only happens inside a live shell.
 */
@PrimaryAdapter
@ShellComponent
internal class BpmnShellCommands(
    private val shellCommands: ObjectProvider<ShellCommands>,
) {
    @ShellMethod(
        key = ["generate", "gen", "g"],
        value = "Generate a BPMN diagram from a natural-language description (interactive).",
    )
    fun generate(
        @ShellOption(help = "What workflow to model, in double quotes") description: String,
        @ShellOption(
            value = ["--output", "-f"],
            defaultValue = "",
            help = "Output .bpmn file name. A descriptive name is generated if omitted.",
        ) outputFile: String = "",
    ): String {
        val rendered = shellCommands.getObject().execute(
            intent = intentFor(description, outputFile),
            open = false,
            showPrompts = false,
            showLlmResponses = false,
            debug = false,
            state = false,
            toolDelay = false,
            operationDelay = false,
            showPlanning = true,
            context = null,
        )
        return withTrailingOutputLocation(rendered)
    }

    // The open-mode pipeline only takes the prose (it seeds UserInput); the output file is set by the
    // gate's LLM draft. So an explicit --output is conveyed as a directive the gate reliably extracts.
    private fun intentFor(
        description: String,
        outputFile: String,
    ): String = if (outputFile.isBlank()) {
        description
    } else {
        "$description\n\nWrite the generated BPMN to the file: ${outputFile.trim()}"
    }

    // Embabel renders the result (incl. the output file) above its cost/tool-usage summary, so the
    // filename is easy to miss. Recover it from BpmnResult.content's marker and echo it as the very
    // last line. The marker is contiguous within embabel's (block-coloured) output, so it matches
    // directly; if absent (clarification/error), the result is returned unchanged.
    private fun withTrailingOutputLocation(rendered: String?): String {
        if (rendered == null) return ""
        val outputPath = OUTPUT_LOCATION.find(rendered)?.groupValues?.get(1)?.trim()
        return if (outputPath.isNullOrBlank()) rendered else "$rendered\n\nWrote BPMN to: $outputPath"
    }

    private companion object {
        val OUTPUT_LOCATION = Regex(Regex.escape(GENERATED_CONTENT_PREFIX) + """(.+?) \(\d+ chars\)""")
    }
}
