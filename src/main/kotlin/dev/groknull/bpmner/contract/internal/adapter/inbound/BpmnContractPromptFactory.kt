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
    ): String = buildString {
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
        appendLine("Activity kind (sealed type with a `kind` discriminator):")
        appendLine(
            "- SERVICE (default) — external/system automation: a backend service call, a job run by" +
                " the system. Example: `{kind: \"SERVICE\", id: \"act-charge-card\", name: \"Charge card\"}`.",
        )
        appendLine(
            "- USER — human work performed through a system UI (a form, a queue, a review screen)." +
                " Example: `{kind: \"USER\", id: \"act-approve\", name: \"Approve loan\"}`.",
        )
        appendLine(
            "- SCRIPT — engine-evaluated computation or transformation, no external service call." +
                " Recognise from phrases like \"the system computes\", \"normalises\", \"transforms\"," +
                " \"calculates\", \"runs a script\". Example:" +
                " `{kind: \"SCRIPT\", id: \"act-normalise\", name: \"Normalise address\"}`.",
        )
        appendLine(
            "- BUSINESS_RULE — a call to a decision model / rule set / decision table. Recognise" +
                " from phrases like \"evaluates the rule set\", \"applies the credit policy\"," +
                " \"decision table\", \"DMN decision\", \"rules engine\". Carries `decisionName`" +
                " (human-readable name of the rule set the LLM identified). Example:" +
                " `{kind: \"BUSINESS_RULE\", id: \"act-credit\", name: \"Evaluate credit policy\"," +
                " decisionName: \"credit policy\"}`.",
        )
        appendLine(
            "- SEND — fire-and-forget outbound message. The workflow does NOT wait for an" +
                " acknowledgement. Recognise from phrases like \"sends a notification\", \"notifies\"," +
                " \"publishes\", \"emits\". Carries `messageName` (human-readable name of the message)." +
                " Example: `{kind: \"SEND\", id: \"act-decline\", name: \"Send decline notification\"," +
                " messageName: \"decline notification\"}`.",
        )
        appendLine(
            "- RECEIVE — wait for an inbound message; the flow BLOCKS until it arrives. Recognise" +
                " from phrases like \"waits for X message\", \"blocks until X is received\", \"the" +
                " customer must confirm\". Name with past-participle (\"X received\"). Carries" +
                " `messageName`. Example: `{kind: \"RECEIVE\", id: \"act-await-ack\"," +
                " name: \"Customer acknowledgement received\", messageName: \"customer acknowledgement\"}`.",
        )
        appendLine(
            "- MANUAL — human work performed WITHOUT system support (paper, in-person, off-system)." +
                " Recognise from phrases like \"manually visits\", \"works with paper notes\"," +
                " \"off-system\". Example: `{kind: \"MANUAL\", id: \"act-inspect\", name: \"Inspect" +
                " property condition\"}`.",
        )
        appendLine()
        appendLine("End-state kind (sealed type with a `kind` discriminator):")
        appendLine(
            "- NORMAL (default) — vanilla path completion; nothing special happens at the end.",
        )
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
                " \"fires X webhook at the end\". Point-to-point send to one recipient." +
                " `messageName` is the human-readable message name from the prose. Example:" +
                " `{kind: \"MESSAGE\", id: \"end-confirmed\", name: \"Shipment confirmation sent\"," +
                " messageName: \"shipment confirmation\"}`.",
        )
        appendLine(
            "- SIGNAL — recognise prose like \"broadcasts X\", \"notifies all subscribers\"," +
                " \"one-to-many notification\". Differs from MESSAGE by being broadcast." +
                " `signalName` is the broadcast name from the prose. Example:" +
                " `{kind: \"SIGNAL\", id: \"end-settled\", name: \"Settlement complete signal\"," +
                " signalName: \"settlement complete\"}`.",
        )
        appendLine(
            "- ESCALATION — recognise prose like \"escalates\", \"notifies the manager\"," +
                " \"flagged for follow-up\". Distinct from ERROR: work isn't broken, it just" +
                " needs intervention. `escalationCode` is the stable business code. Example:" +
                " `{kind: \"ESCALATION\", id: \"end-overdue\", name: \"Approval overdue escalation\"," +
                " escalationCode: \"APPROVAL_OVERDUE\"}`.",
        )
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
