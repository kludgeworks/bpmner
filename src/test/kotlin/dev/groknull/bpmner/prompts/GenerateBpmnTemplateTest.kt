/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("TooManyFunctions")

package dev.groknull.bpmner.prompts

import com.embabel.common.textio.template.JinjavaTemplateRenderer
import dev.groknull.bpmner.contract.ConditionalBranch
import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractActor
import dev.groknull.bpmner.contract.ContractAssumption
import dev.groknull.bpmner.contract.ContractDecision
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.DefaultBranch
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.ProcessContractMarkdownRenderer
import dev.groknull.bpmner.core.BpmnNamingShapeAdvice
import dev.groknull.bpmner.core.BpmnRequest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Substring coverage for the BPMN-generation template. Mirrors the assertions from the
 * legacy BpmnContractGenerationPromptFactoryTest against the rendered template output.
 */
class GenerateBpmnTemplateTest {
    private val renderer = JinjavaTemplateRenderer()
    private val contractRenderer = ProcessContractMarkdownRenderer()

    @Test
    fun `template renders a contract-first BPMN generation prompt with traceability context`() {
        val prompt = render(
            request = BpmnRequest(
                processDescription = "Customer submits a claim and the team reviews it.",
                styleGuide = "Use sentence case task names.",
            ),
            contract = claimContract(),
        )

        assertTrue(prompt.contains("validated ProcessContract is the primary and authoritative generation input"))
        assertTrue(prompt.contains("Original input for traceability only:"))
        assertTrue(
            prompt.indexOf("Primary validated ProcessContract:") <
                prompt.indexOf("Original input for traceability only:"),
        )
        assertTrue(prompt.contains("Trigger: Claim is submitted"))
        assertTrue(prompt.contains("- a-review: Claims team reviews claim (actor: actor-claims)"))
        assertTrue(prompt.contains("- a-rework: Request corrected claim details"))
        assertTrue(prompt.contains("- d-complete: Is the claim complete?"))
        assertTrue(prompt.contains("- b-complete"))
        assertTrue(prompt.contains("- b-rework"))
        assertTrue(prompt.contains("- end-approved: Claim approved"))
        assertTrue(prompt.contains("- end-rejected: Claim rejected"))
        assertTrue(prompt.contains("- assume-cutoff: Claims after cutoff move to next business day"))
        assertTrue(prompt.contains("Use sentence case task names."))
        assertTrue(prompt.contains("Do not add unsupported tasks, decisions, branches, actors, or end states."))
        assertTrue(prompt.contains("infer sequence flows and routing-only converging gateways"))
        assertTrue(prompt.contains("Leave routing-only converging gateways unnamed."))
    }

    @Test
    fun `template includes naming-shape rules for every BpmnNode subtype`() {
        val prompt = render(
            request = BpmnRequest(processDescription = "anything"),
            contract = creditTierContract(),
        )
        assertTrue(prompt.contains("Naming shape rules"))
        assertTrue(prompt.contains("START_EVENT:"))
        assertTrue(prompt.contains("END_EVENT:"))
        assertTrue(prompt.contains("past-tense", ignoreCase = true))
        assertTrue(prompt.contains("verb-object", ignoreCase = true))
    }

    @Test
    fun `template softens DefaultBranch guidance to defer isDefault to the assigner`() {
        val prompt = render(
            request = BpmnRequest(processDescription = "anything"),
            contract = creditTierContract(),
        )
        assertTrue(prompt.contains("downstream DefaultFlowAssigner"))
        assertTrue(prompt.contains("NEVER invent"))
    }

    @Test
    fun `template teaches the load-bearing branch wiring not the schema-covered kind mapping`() {
        val prompt = render(
            request = BpmnRequest(processDescription = "Route credit applications by score."),
            contract = creditTierContract(),
        )

        // The bare kind -> BpmnEdge mappings live in the ContractBranch subtype schema and the
        // conditionExpression/isDefault field descriptions. The template keeps only what the schema
        // can't express: the DefaultFlowAssigner hand-off + the anti-pattern.
        assertTrue(prompt.contains("Branch wiring"))
        assertTrue(prompt.contains("downstream DefaultFlowAssigner"))
        assertTrue(prompt.contains("NEVER invent"))
        // The per-kind class mapping is not restated in prose.
        assertTrue(
            !prompt.contains("(ConditionalBranch) → BpmnEdge"),
            "schema-covered kind->edge mapping should not be restated in the template",
        )
        assertTrue(
            prompt.contains("- b-manual → \"Manual review\" [default]"),
            "rendered decision branches should mark the default with [default]; got:\n$prompt",
        )
    }

    private fun render(request: BpmnRequest, contract: ProcessContract): String {
        return renderer.renderLoadedTemplate("bpmner/generate_bpmn", model(request, contract))
    }

    private fun model(request: BpmnRequest, contract: ProcessContract): Map<String, Any> = mapOf(
        "contractMarkdown" to contractRenderer.render(contract).trim(),
        "processDescription" to request.processDescription,
        "styleGuide" to (request.styleGuide ?: ""),
        "namingShapeAdvice" to BpmnNamingShapeAdvice.allAdvice().map { advice ->
            val examples = advice.examples.joinToString(", ") { "\"$it\"" }
            val avoid = advice.antiExamples.joinToString(", ") { "\"$it\"" }
            "- ${advice.kind}: ${advice.shape}\n    examples: $examples\n    avoid:    $avoid"
        },
    )

    private fun creditTierContract(): ProcessContract {
        val sources = listOf("ev1")
        return ProcessContract(
            id = "contract-credit-tier",
            processName = "Credit-tier routing",
            summary = "Loan applications routed by credit-bureau score to one of three underwriting paths.",
            trigger = "Credit-check subprocess returns a score",
            triggerSourceIds = sources,
            activities = listOf(
                ContractActivity(id = "a-fast", name = "Fast-track underwriting", sourceIds = sources),
                ContractActivity(id = "a-standard", name = "Standard underwriting", sourceIds = sources),
                ContractActivity(id = "a-manual", name = "Manual review", sourceIds = sources),
            ),
            decisions = listOf(
                ContractDecision(
                    id = "d-tier",
                    question = "Which credit tier?",
                    branches = listOf(
                        ConditionalBranch(id = "b-fast", label = "Fast-track", condition = "score >= 750"),
                        ConditionalBranch(id = "b-standard", label = "Standard", condition = "score in 600..749"),
                        DefaultBranch(id = "b-manual", label = "Manual review"),
                    ),
                    sourceIds = sources,
                ),
            ),
            endStates = listOf(ContractEndState(id = "end-offer", name = "Offer generated", sourceIds = sources)),
        )
    }

    private fun claimContract(): ProcessContract {
        val sources = listOf("ev1")
        return ProcessContract(
            id = "contract-claim",
            processName = "Handle claim",
            summary = "Claims are reviewed, routed for rework when incomplete, and closed.",
            trigger = "Claim is submitted",
            triggerSourceIds = sources,
            actors = listOf(ContractActor(id = "actor-claims", name = "Claims team")),
            activities = listOf(
                ContractActivity(
                    id = "a-review",
                    name = "Claims team reviews claim",
                    actorId = "actor-claims",
                    sourceIds = sources,
                ),
                ContractActivity(
                    id = "a-rework",
                    name = "Request corrected claim details",
                    actorId = "actor-claims",
                    sourceIds = sources,
                ),
            ),
            decisions = listOf(
                ContractDecision(
                    id = "d-complete",
                    question = "Is the claim complete?",
                    branches = listOf(
                        ConditionalBranch(id = "b-complete", label = "Complete", condition = "claim is complete"),
                        ConditionalBranch(
                            id = "b-rework",
                            label = "Needs rework",
                            condition = "claim is incomplete",
                        ),
                    ),
                    sourceIds = sources,
                ),
            ),
            endStates = listOf(
                ContractEndState(id = "end-approved", name = "Claim approved", sourceIds = sources),
                ContractEndState(id = "end-rejected", name = "Claim rejected", sourceIds = sources),
            ),
            assumptions = listOf(
                ContractAssumption(
                    id = "assume-cutoff",
                    text = "Claims after cutoff move to next business day",
                    sourceIds = sources,
                ),
            ),
        )
    }
}
