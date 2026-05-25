/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.adapter.inbound

import dev.groknull.bpmner.contract.ConditionalBranch
import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractActor
import dev.groknull.bpmner.contract.ContractAssumption
import dev.groknull.bpmner.contract.ContractDecision
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.ContractValidationReport
import dev.groknull.bpmner.contract.DefaultBranch
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.ProcessContractMarkdownRenderer
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.core.BpmnRequest
import kotlin.test.Test
import kotlin.test.assertTrue

class BpmnContractGenerationPromptFactoryTest {
    private val factory = BpmnContractGenerationPromptFactory(ProcessContractMarkdownRenderer())

    @Test
    fun `builds a contract-first BPMN generation prompt with traceability context`() {
        val prompt =
            factory.prompt(
                request =
                BpmnRequest(
                    processDescription = "Customer submits a claim and the team reviews it.",
                    styleGuide = "Use sentence case task names.",
                ),
                validatedContract = ValidatedProcessContract(contract(), ContractValidationReport(emptyList())),
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
    fun `prompt includes naming-shape rules for every BpmnNode subtype`() {
        val prompt =
            factory.prompt(
                request = BpmnRequest(processDescription = "anything"),
                validatedContract = ValidatedProcessContract(creditTierContract(), ContractValidationReport(emptyList())),
            )
        assertTrue(prompt.contains("Naming shape rules"))
        assertTrue(prompt.contains("START_EVENT:"))
        assertTrue(prompt.contains("END_EVENT:"))
        assertTrue(prompt.contains("past-tense", ignoreCase = true))
        assertTrue(prompt.contains("verb-object", ignoreCase = true))
    }

    @Test
    fun `prompt softens DefaultBranch guidance to defer isDefault to the assigner`() {
        val prompt =
            factory.prompt(
                request = BpmnRequest(processDescription = "anything"),
                validatedContract = ValidatedProcessContract(creditTierContract(), ContractValidationReport(emptyList())),
            )
        // Softened wording: the LLM emits the flow; the assigner sets the flag.
        assertTrue(prompt.contains("downstream DefaultFlowAssigner"))
        assertTrue(prompt.contains("NEVER invent"))
    }

    @Test
    fun `prompt teaches the LLM the branch-kind to BpmnEdge mapping`() {
        val prompt =
            factory.prompt(
                request = BpmnRequest(processDescription = "Route credit applications by score."),
                validatedContract = ValidatedProcessContract(creditTierContract(), ContractValidationReport(emptyList())),
            )

        // Mapping table covers all three kinds.
        assertTrue(prompt.contains("CONDITIONAL (ConditionalBranch) → BpmnEdge with `conditionExpression = branch.condition`"))
        // DEFAULT guidance is now softened — the LLM emits the flow; the assigner sets isDefault.
        assertTrue(prompt.contains("DEFAULT (DefaultBranch) → emit an outbound BpmnEdge with `conditionExpression = null`"))
        assertTrue(prompt.contains("UNCONDITIONAL (UnconditionalBranch) → BpmnEdge with neither condition nor isDefault"))

        // Rendered contract surfaces the [default] marker.
        assertTrue(
            prompt.contains("- b-manual → \"Manual review\" [default]"),
            "rendered decision branches should mark the default with [default]; got:\n$prompt",
        )
    }

    private fun creditTierContract(): ProcessContract {
        val sources = listOf("ev1")
        return ProcessContract(
            id = "contract-credit-tier",
            processName = "Credit-tier routing",
            summary = "Loan applications routed by credit-bureau score to one of three underwriting paths.",
            trigger = "Credit-check subprocess returns a score",
            triggerSourceIds = sources,
            activities =
            listOf(
                ContractActivity(id = "a-fast", name = "Fast-track underwriting", sourceIds = sources),
                ContractActivity(id = "a-standard", name = "Standard underwriting", sourceIds = sources),
                ContractActivity(id = "a-manual", name = "Manual review", sourceIds = sources),
            ),
            decisions =
            listOf(
                ContractDecision(
                    id = "d-tier",
                    question = "Which credit tier?",
                    branches =
                    listOf(
                        ConditionalBranch(
                            id = "b-fast",
                            label = "Fast-track",
                            condition = "score >= 750",
                        ),
                        ConditionalBranch(
                            id = "b-standard",
                            label = "Standard",
                            condition = "score in 600..749",
                        ),
                        DefaultBranch(id = "b-manual", label = "Manual review"),
                    ),
                    sourceIds = sources,
                ),
            ),
            endStates =
            listOf(
                ContractEndState(id = "end-offer", name = "Offer generated", sourceIds = sources),
            ),
        )
    }

    @Suppress("LongMethod") // exhaustive contract fixture — splitting it obscures the assertions
    private fun contract(): ProcessContract {
        val sources = listOf("ev1")
        return ProcessContract(
            id = "contract-claim",
            processName = "Handle claim",
            summary = "Claims are reviewed, routed for rework when incomplete, and closed.",
            trigger = "Claim is submitted",
            triggerSourceIds = sources,
            actors = listOf(ContractActor(id = "actor-claims", name = "Claims team")),
            activities =
            listOf(
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
            decisions =
            listOf(
                ContractDecision(
                    id = "d-complete",
                    question = "Is the claim complete?",
                    branches =
                    listOf(
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
            endStates =
            listOf(
                ContractEndState(
                    id = "end-approved",
                    name = "Claim approved",
                    sourceIds = sources,
                ),
                ContractEndState(
                    id = "end-rejected",
                    name = "Claim rejected",
                    sourceIds = sources,
                ),
            ),
            assumptions =
            listOf(
                ContractAssumption(
                    id = "assume-cutoff",
                    text = "Claims after cutoff move to next business day",
                    sourceIds = sources,
                ),
            ),
        )
    }
}
