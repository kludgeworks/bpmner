/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.config

import com.embabel.common.ai.prompt.PromptContributor

/** Port for generation-owned request prompt contribution behavior. */
fun interface BpmnRequestPromptContributor {
    fun contributionFor(styleGuide: String?): PromptContributor
}
