/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract.internal.adapter.inbound

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
    ): String = buildString {
        appendLine("Return only a structured ${FlatProcessContract::class.simpleName} object.")
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
        appendLine("- Return the process start in `start` with a `trigger` object and `sourceIds`.")
        appendLine("- `start.sourceIds` must list at least one source id grounding the trigger.")
        appendLine(
            "- Schedule language (dates, delays, cycles, cron-like intervals): set" +
                " `trigger.type` = TIMER and populate `timerKind` + `expression` + `description`.",
        )
        appendLine(
            "- Webhook/API/event-bus triggers intended for one process/listener: set" +
                " `trigger.type` = MESSAGE and populate `messageName` + `description`.",
        )
        appendLine(
            "- Broadcast or multi-listener triggers: set `trigger.type` = SIGNAL and populate" +
                " `signalName` + `description`.",
        )
        appendLine("- Ordinary untyped process starts: set `trigger.type` = NONE and populate `description`.")
        appendLine()
        appendLine("Activity kind (`kind` discriminator); SERVICE is the default:")
        appendLine("- SERVICE — Example: `{kind: \"SERVICE\", id: \"act-charge-card\", name: \"Charge card\"}`.")
        appendLine(
            "- USER — through a form, queue, or review screen." +
                " Example: `{kind: \"USER\", id: \"act-approve\", name: \"Approve loan\"}`.",
        )
        appendLine(
            "- SCRIPT — Recognise from phrases like \"the system computes\", \"normalises\"," +
                " \"transforms\", \"calculates\", \"runs a script\". Example:" +
                " `{kind: \"SCRIPT\", id: \"act-normalise\", name: \"Normalise address\"}`.",
        )
        appendLine(
            "- BUSINESS_RULE — Recognise from phrases like \"evaluates the rule set\"," +
                " \"applies the credit policy\", \"decision table\", \"DMN decision\", \"rules engine\"." +
                " Carries `decisionName`. Example:" +
                " `{kind: \"BUSINESS_RULE\", id: \"act-credit\", name: \"Evaluate credit policy\"," +
                " decisionName: \"credit policy\"}`.",
        )
        appendLine(
            "- SEND — The workflow does NOT wait for an acknowledgement. Recognise from phrases" +
                " like \"sends a notification\", \"notifies\", \"publishes\", \"emits\". Carries" +
                " `messageName`. Example: `{kind: \"SEND\", id: \"act-decline\"," +
                " name: \"Send decline notification\", messageName: \"decline notification\"}`.",
        )
        appendLine(
            "- RECEIVE — the flow BLOCKS until the message arrives. Recognise from phrases like" +
                " \"waits for X message\", \"blocks until X is received\", \"the customer must" +
                " confirm\". Name with past-participle (\"X received\"). Carries `messageName`." +
                " Example: `{kind: \"RECEIVE\", id: \"act-await-ack\"," +
                " name: \"Customer acknowledgement received\", messageName: \"customer acknowledgement\"}`.",
        )
        appendLine(
            "- MANUAL — paper, in-person, or off-system. Recognise from phrases like" +
                " \"manually visits\", \"works with paper notes\", \"off-system\". Example:" +
                " `{kind: \"MANUAL\", id: \"act-inspect\", name: \"Inspect property condition\"}`.",
        )
        appendLine()
        appendLine("End-state kind (`kind` discriminator); NORMAL is the default:")
        appendLine(
            "- TERMINATE — recognise prose like \"process terminates immediately\", \"every" +
                " in-flight track stops\", \"abandons all work\", \"the entire workflow ends\"." +
                " Use when one path's completion must KILL every other parallel path. Example:" +
                " `{kind: \"TERMINATE\", id: \"end-cancelled\", name: \"Booking cancelled\"}`.",
        )
        appendLine(
            "- ERROR — recognise prose like \"raises an error\", \"throws X error\"," +
                " \"propagates failure up\", \"bubbles up to a catcher\". `errorCode` is the" +
                " stable BUSINESS error code that catchers match (e.g. \"CREDIT_REJECTED\")," +
                " NOT a user-facing message. Example: `{kind: \"ERROR\", id: \"end-rejected\"," +
                " name: \"Credit rejected\", errorCode: \"CREDIT_REJECTED\"}`.",
        )
        appendLine(
            "- MESSAGE — recognise prose like \"sends X on completion\", \"wraps up by sending\"," +
                " \"fires X webhook at the end\". Example:" +
                " `{kind: \"MESSAGE\", id: \"end-confirmed\", name: \"Shipment confirmation sent\"," +
                " messageName: \"shipment confirmation\"}`.",
        )
        appendLine(
            "- SIGNAL — recognise prose like \"broadcasts X\", \"notifies all subscribers\"," +
                " \"one-to-many notification\". Example:" +
                " `{kind: \"SIGNAL\", id: \"end-settled\", name: \"Settlement complete signal\"," +
                " signalName: \"settlement complete\"}`.",
        )
        appendLine(
            "- ESCALATION — recognise prose like \"escalates\", \"notifies the manager\"," +
                " \"flagged for follow-up\". Example:" +
                " `{kind: \"ESCALATION\", id: \"end-overdue\", name: \"Approval overdue escalation\"," +
                " escalationCode: \"APPROVAL_OVERDUE\"}`.",
        )
        appendLine()
        appendLine("Branch kind (`kind` discriminator):")
        appendLine(
            "- CONDITIONAL — the default branch kind on EXCLUSIVE decisions. Example:" +
                " `{kind: \"CONDITIONAL\", id: \"br-yes\", label: \"Eligible\", condition: \"score >= 750\"}`.",
        )
        appendLine(
            "- DEFAULT — set this when the source prose uses catch-all phrasing — \"otherwise\"," +
                " \"for every other case\", \"the catch-all\", \"anything else\", \"for all remaining" +
                " cases\". Carries NO condition; the label still describes the destination" +
                " (e.g. \"Manual review\"). Example:" +
                " `{kind: \"DEFAULT\", id: \"br-fallback\", label: \"Manual review\"}`. At most one" +
                " DEFAULT branch per decision.",
        )
        appendLine(
            "- UNCONDITIONAL — Example:" +
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
                " set the branch's `nextRef` to the id of the activity that the iteration loops back to.",
        )
        appendLine(
            "- When a decision has multiple exit conditions (e.g. pass, no-progress, exhausted)," +
                " declare each as a separate branch with its own condition. Set `nextRef` on the" +
                " looping branch; leave it null on the exiting branches.",
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
        appendLine("Decision kind (exclusive vs inclusive vs parallel); EXCLUSIVE is the default:")
        appendLine(
            "- EXCLUSIVE — use for any branching where the source describes a choice, a check," +
                " or alternative paths where only one option is taken at a time.",
        )
        appendLine(
            "- INCLUSIVE — recognise from phrases like 'any of the following can fire', 'optional" +
                " gift wrap AND/OR optional promotional insert', 'each is evaluated independently'." +
                " INCLUSIVE branches carry `condition` expressions like EXCLUSIVE; the conditions" +
                " are evaluated independently and any subset (including all of them) may evaluate" +
                " true. Declare the fork only; the matching join is materialised downstream.",
        )
        appendLine(
            "- PARALLEL — recognise from phrases like 'in parallel', 'simultaneously', 'all of" +
                " the following must complete', or any split where every branch must execute, not" +
                " just one. PARALLEL branches have no `condition`; leave it null. Declare the fork" +
                " only; the matching join (synchronisation) is implicit and materialised downstream.",
        )
        appendLine(
            "- Do NOT use PARALLEL for sequential steps that happen to share an actor or context;" +
                " parallel means truly concurrent, with no fixed ordering between the tracks.",
        )
        appendLine(
            "- INCLUSIVE vs PARALLEL: PARALLEL fires every branch unconditionally; INCLUSIVE fires" +
                " each branch only if its condition is true (some, all, or none may fire). If every" +
                " branch in the source description always fires regardless of conditions, use" +
                " PARALLEL. If the source distinguishes which conditions apply per case, use" +
                " INCLUSIVE.",
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
