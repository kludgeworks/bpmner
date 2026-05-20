/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract.internal.adapter.inbound

import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.core.BpmnContractConfig
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.ClarificationExchange
import dev.groknull.bpmner.readiness.ProcessInputAssessment

internal class BpmnContractPromptFactory(
    private val config: BpmnContractConfig,
) {
    @Suppress("LongMethod") // prompt assembly is a single linear narrative; splitting hurts readability
    fun prompt(
        request: BpmnRequest,
        assessment: ProcessInputAssessment,
        clarificationHistory: List<ClarificationExchange>,
    ): String =
        buildString {
            appendLine("Return only a structured ${ProcessContract::class.simpleName} object.")
            appendLine()
            appendLine("Extract a source-grounded process contract from the supplied inputs.")
            appendLine("Do not invent actors, triggers, end states, decisions, branches, or artifacts.")
            appendLine(
                "If a fact required for a complete contract is not present in the source text or" +
                    " clarification answers, record it as a ContractAssumption with at least one sourceId.",
            )
            appendLine("Cap assumptions at ${config.maxAssumptions}.")
            appendLine()
            appendLine("Source grounding (provenance):")
            appendLine(
                "- Every ContractActivity, ContractDecision, ContractEndState, and ContractAssumption" +
                    " must list at least one entry in its `sourceIds` field.",
            )
            appendLine(
                "- A source id is an assessment evidence id, a clarification questionId, or a literal" +
                    " input-text marker.",
            )
            appendLine("- The process start must be returned in `start` as `ContractStart(trigger, sourceIds)`.")
            appendLine("- `start.sourceIds` must list at least one source id grounding the trigger.")
            appendLine(
                "- Extract schedule language (dates, delays, cycles, cron-like intervals) as" +
                    " `ContractTrigger.Timer(timerKind, expression, description)`.",
            )
            appendLine(
                "- Extract webhook/API/event-bus triggers intended for one process/listener as" +
                    " `ContractTrigger.Message(messageName, description)`.",
            )
            appendLine(
                "- Extract broadcast or multi-listener triggers as" +
                    " `ContractTrigger.Signal(signalName, description)`.",
            )
            appendLine("- Use `ContractTrigger.None(description)` for ordinary untyped process starts.")
            appendLine()
            appendLine("Branch kind (sealed type with a `kind` discriminator):")
            appendLine(
                "- CONDITIONAL — the default kind for branches of an EXCLUSIVE decision. Carries a" +
                    " non-blank `condition` expression that selects it. Example:" +
                    " `{kind: \"CONDITIONAL\", id: \"br-yes\", label: \"Eligible\", condition: \"score >= 750\"}`.",
            )
            appendLine(
                "- DEFAULT — the catch-all branch of an EXCLUSIVE decision, taken when no other" +
                    " branch's condition matched. Set this when the source prose uses catch-all" +
                    " phrasing — \"otherwise\", \"for every other case\", \"the catch-all\", \"anything else\"," +
                    " \"for all remaining cases\". A DEFAULT branch carries NO condition; the label" +
                    " still describes the destination (e.g. \"Manual review\"). Example:" +
                    " `{kind: \"DEFAULT\", id: \"br-fallback\", label: \"Manual review\"}`. At most one" +
                    " DEFAULT branch per decision.",
            )
            appendLine(
                "- UNCONDITIONAL — the kind for branches of a PARALLEL decision. Every branch fires" +
                    " concurrently; no condition. Example:" +
                    " `{kind: \"UNCONDITIONAL\", id: \"br-track-a\", label: \"IT prep\"}`.",
            )
            appendLine(
                "- Kind/decision matching is strict: CONDITIONAL and DEFAULT only on EXCLUSIVE decisions;" +
                    " UNCONDITIONAL only on PARALLEL decisions.",
            )
            appendLine()
            appendLine("Loop and topology rules:")
            appendLine(
                "- When the source describes an iterative process (retry / repair / poll / until / while)," +
                    " set `ContractBranch.nextRef` to the id of the activity that the iteration loops back to.",
            )
            appendLine(
                "- When a decision has multiple exit conditions (e.g. pass, no-progress, exhausted)," +
                    " declare each as a separate ContractBranch with its own condition. Set `nextRef`" +
                    " on the looping branch; leave it null on the exiting branches.",
            )
            appendLine(
                "- Branch ids referenced via `nextRef` must exist elsewhere in `activities`," +
                    " `decisions`, or `endStates`.",
            )
            appendLine(
                "- For sequential (non-looping) flow, leave `nextRef` null; the branch is assumed to" +
                    " lead to the next sequential element.",
            )
            appendLine()
            appendLine("Decision kind (exclusive vs parallel):")
            appendLine(
                "- `ContractDecision.kind` defaults to EXCLUSIVE — exactly one branch is taken based" +
                    " on its `condition`. Use this for any branching where the source describes a choice," +
                    " a check, or alternative paths.",
            )
            appendLine(
                "- Set `kind = PARALLEL` when the source describes concurrent / independent tracks," +
                    " 'in parallel', 'simultaneously', 'all of the following must complete', or any" +
                    " split where every branch must execute, not just one. PARALLEL branches have no" +
                    " `condition`; leave it null. The matching join (synchronisation) is implicit and" +
                    " materialised in the downstream BPMN — declare the fork only.",
            )
            appendLine(
                "- Do NOT use PARALLEL for sequential steps that happen to share an actor or context;" +
                    " parallel means truly concurrent, with no fixed ordering between the tracks.",
            )
            appendLine()
            appendLine("Readiness assessment rationale:")
            appendLine(assessment.rationale)
            if (assessment.missingAreas.isNotEmpty()) {
                appendLine()
                appendLine("Missing process areas to surface as assumptions or clarifications:")
                assessment.missingAreas.forEach { appendLine("- ${it.name}") }
            }
            if (assessment.evidence.isNotEmpty()) {
                appendLine()
                appendLine("Assessment evidence ids available for use in `sourceIds`:")
                assessment.evidence.forEach { appendLine("- ${it.id}: ${it.text}") }
            }
            if (clarificationHistory.isNotEmpty()) {
                appendLine()
                appendLine("Clarification answers (in order). Use questionId in `sourceIds` when relevant:")
                clarificationHistory.forEach {
                    appendLine("- [${it.questionId}] Q: ${it.questionText}")
                    appendLine("  A: ${it.answerText}")
                }
            }
            if (!request.styleGuide.isNullOrBlank()) {
                appendLine()
                appendLine("Style guide (constraints on naming and structure):")
                appendLine(request.styleGuide)
            }
            appendLine()
            appendLine("Original BPMN request text:")
            appendLine(request.processDescription)
        }
}
