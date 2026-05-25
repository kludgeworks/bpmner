/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.readiness.internal.adapter.inbound

import dev.groknull.bpmner.core.BpmnReadinessConfig
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.MissingProcessArea
import dev.groknull.bpmner.core.ReadinessDimension
import dev.groknull.bpmner.readiness.ProcessInputAssessment

internal class BpmnReadinessPromptFactory(
    private val config: BpmnReadinessConfig,
) {
    @Suppress("LongMethod")
    fun prompt(request: BpmnRequest): String = buildString {
        appendLine("Return only a structured ${ProcessInputAssessment::class.simpleName} object.")
        appendLine()
        appendLine(
            "JSON formatting: when any string field (especially evidence.text) needs to mention a" +
                " quoted term from the source, escape every inner double quote as \\\" or use single" +
                " quotes or backticks. Never let an unescaped \" appear inside a JSON string value.",
        )
        appendLine()
        appendLine("Assess whether the source text describes a workflow that is ready for BPMN modelling.")
        appendLine()
        appendLine("What counts as a workflow:")
        appendLine("- A workflow is any repeatable, ordered sequence of activities with a clear start and end.")
        appendLine("- Domain is irrelevant. Valid workflows include, but are not limited to:")
        appendLine("  * business processes (orders, approvals, claims, onboarding)")
        appendLine("  * automated or service pipelines (data ingest, ETL, CI/CD, request handling)")
        appendLine("  * technical or operational procedures (incident response, deployment, repair loops)")
        appendLine("  * scientific or clinical procedures (lab protocols, triage)")
        appendLine("  * personal or domestic routines (cooking, travel, study)")
        appendLine("- Actors may be human roles, organisations, software agents, services, or systems.")
        appendLine("  Software agents and automated services are valid BPMN actors/lanes — do not penalise")
        appendLine("  inputs for lacking human participants.")
        appendLine("- BPMN 2.0 explicitly supports service tasks, send/receive tasks, and system lanes.")
        appendLine("  An automated or technical pipeline is BPMN-suitable whenever it has the structural")
        appendLine("  elements (trigger, ordered activities, decisions where present, end state).")
        appendLine()
        appendLine("Evaluate substance, not framing:")
        appendLine("- The source text may include commentary, instructions, headings, or meta-prose.")
        appendLine("- Score based on whether a sequenced workflow can be extracted from the substantive")
        appendLine("  content, not on whether the document is shaped as a process description.")
        appendLine()
        appendLine("Grounding rules (these stay strict):")
        appendLine("- Do not invent actors, triggers, end states, exceptions, artifacts, or activities.")
        appendLine("- Mark unsupported facts as missing rather than filling gaps from assumptions.")
        appendLine("- Ground evidence only in exact or narrowly paraphrased source text.")
        appendLine()
        appendLine("Verdict rules:")
        appendLine("- READY when overallScore >= ${config.readyThreshold}.")
        appendLine("- NEEDS_CLARIFICATION when overallScore < ${config.readyThreshold}.")
        appendLine("- NEEDS_CLARIFICATION also covers inputs with no workflow signal at all;")
        appendLine("  never penalise a workflow solely for being automated, technical, or non-business.")
        appendLine()
        appendLine("Score every readiness dimension:")
        ReadinessDimension.entries.forEach { appendLine("- ${it.name}") }
        appendLine()
        appendLine("Per-dimension guidance:")
        appendLine("- BPMN_SUITABILITY: A workflow qualifies if it has trigger, ordered activities, and")
        appendLine("  end state. Automated, technical, and operational pipelines qualify. Score low only")
        appendLine("  when no sequenced workflow exists (e.g. static descriptions, opinions, UI specs).")
        appendLine("- ACTORS_ROLES: Software agents, services, systems, and named components count as")
        appendLine("  valid actors. Do not score low just because actors are non-human.")
        appendLine("- SCOPE_CLARITY: A technical pipeline or service flow is a valid scope.")
        appendLine("- TRACEABILITY_TO_SOURCE: Self-referential text (a document describing its own system)")
        appendLine("  is still valid source — traceability is about evidence in the text, not externality.")
        appendLine()
        appendLine("Use missing process areas only from this vocabulary:")
        MissingProcessArea.entries.forEach { appendLine("- ${it.name}") }
        appendLine()
        appendLine("Clarification questions:")
        appendLine("- Ask at most ${config.maxClarificationQuestions} questions.")
        appendLine("- Make each question specific and answerable.")
        appendLine("- Tie every question to relatedDimensions and relatedMissingAreas.")
        appendLine()
        appendLine("Original BPMN request text:")
        appendLine(request.processDescription)
        if (request.clarificationHistory.isNotEmpty()) {
            appendLine()
            appendLine("Prior clarification answers:")
            request.clarificationHistory.forEach {
                appendLine("- [${it.questionId}] Q: ${it.questionText}")
                appendLine("  A: ${it.answerText}")
            }
            appendLine()
            appendLine("Re-assess readiness using both the original text and clarification answers.")
        }
    }
}
