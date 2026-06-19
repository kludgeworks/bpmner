/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation

import com.embabel.common.ai.prompt.PromptContributor
import dev.groknull.bpmner.config.BpmnRequestPromptContributor
import org.springframework.stereotype.Component

/** Thin generation-owned adapter for request style-guide prompt contribution behavior. */
@Component
internal class GenerationBpmnRequestPromptContributor : BpmnRequestPromptContributor {
    override fun contributionFor(styleGuide: String?): PromptContributor {
        val contribution = styleGuide?.let { "## Style guide\n\n$it" } ?: ""
        return PromptContributor.fixed(contribution)
    }
}
