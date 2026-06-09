/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.adapter.inbound

import com.embabel.agent.shell.ShellCommands
import org.jmolecules.architecture.hexagonal.PrimaryAdapter
import org.springframework.beans.factory.ObjectProvider
import org.springframework.shell.standard.ShellComponent
import org.springframework.shell.standard.ShellMethod
import org.springframework.shell.standard.ShellOption

/**
 * Dedicated shell entrypoint that runs the BPMN pipeline without the `-o` flag.
 *
 * Embabel's built-in `x`/`execute` defaults to **closed mode** (one agent in isolation), which gets
 * stuck because the BPMN pipeline spans several agents (gate → contract → generator → alignment →
 * repair → layout). **Open mode** (`x ... -o`) composes across all agents and works. This command
 * delegates to embabel's `execute` with open mode forced on, reusing its goal composition,
 * interactive clarification (HITL) loop, and result formatting — so `generate "<description>"` just
 * works.
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
    ): String = shellCommands.getObject().execute(
        intent = description,
        open = true,
        showPrompts = false,
        showLlmResponses = false,
        debug = false,
        state = false,
        toolDelay = false,
        operationDelay = false,
        showPlanning = true,
        context = null,
    )
}
