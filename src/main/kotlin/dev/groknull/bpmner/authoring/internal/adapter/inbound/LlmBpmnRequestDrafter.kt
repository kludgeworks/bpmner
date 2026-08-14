/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring.internal.adapter.inbound

import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.domain.io.UserInput
import com.embabel.chat.UserMessage
import dev.groknull.bpmner.authoring.BpmnRequestDraft
import dev.groknull.bpmner.authoring.BpmnRequestDrafter
import dev.groknull.bpmner.readiness.BpmnReadinessConfig
import org.jmolecules.architecture.onion.simplified.InfrastructureRing
import org.springframework.stereotype.Component

@InfrastructureRing
@Component
internal class LlmBpmnRequestDrafter(
    private val config: BpmnReadinessConfig,
) : BpmnRequestDrafter {

    override fun draftRequest(
        userInput: UserInput,
        context: OperationContext,
    ): BpmnRequestDraft {
        val prompt =
            """
            Route a BPMN generation request from the user's shell instruction.

            Do not repeat the workflow prose back. When the user describes a workflow directly, the
            system reads it from this instruction verbatim; your job is only to say where the prose
            lives and where the output goes.

            Rules:
            - Set processFile only when the user explicitly says the workflow is in a file.
              Leave it null when the user described the workflow directly.
            - Always set outputFile. If the user named a specific output file, use it exactly;
              otherwise generate a concise, lowercase, kebab-case name ending in .bpmn derived from
              the process, with no directories or spaces (e.g. purchase-order-approval.bpmn).
            - Put inline style guidance in styleGuide, or a style-guide file path in styleGuideFile.
            - Do not invent input files (processFile) or style-guide files.

            User instruction:
            ${userInput.content}
            """.trimIndent()

        return config.readinessAssessor
            .promptRunner(context)
            .creating(BpmnRequestDraft::class.java)
            .fromMessages(listOf(UserMessage(prompt)))
    }
}
