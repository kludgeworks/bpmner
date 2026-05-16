/*
 * Copyright (c) 2026 The Project Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package dev.groknull.bpmner.validation.internal.domain

import com.embabel.agent.api.common.PromptRunner
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.validation.BpmnDiagnostic
import dev.groknull.bpmner.validation.BpmnRuleGuidancePort
import dev.groknull.bpmner.validation.internal.adapter.outbound.RuleCatalogService
import org.springframework.stereotype.Component

@Component
internal class LlmValidator(
    private val catalogService: RuleCatalogService,
) : BpmnRuleGuidancePort {
    fun validate(
        _definition: BpmnDefinition,
        _graph: LaidOutProcessGraph,
        _promptRunner: PromptRunner,
    ): List<BpmnDiagnostic> {
        val llmRules = catalogService.catalog.rules.filter { !it.hasTsImplementation }
        if (llmRules.isEmpty()) return emptyList()

        // In production this would invoke an LLM agent for heuristic validation.
        // Currently, rule guidance is passed to the repair prompt factory instead
        // so the repair agent performs just-in-time validation and repair.

        return emptyList()
    }

    override fun getLlmRuleGuidance(): String {
        val llmRules = catalogService.catalog.rules.filter { !it.hasTsImplementation }
        if (llmRules.isEmpty()) return ""

        return buildString {
            appendLine("General Workflow Rule Guidance (Heuristic):")
            for (rule in llmRules) {
                appendLine("## ${rule.id}: ${rule.name}")
                appendLine("Intent: ${rule.intent}")
                appendLine("Guidance: ${rule.forAI}")
                appendLine()
            }
        }
    }
}
