/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules

/**
 * Modelling-team lint configuration loaded from the packaged `bpmner.pkl` template at startup.
 *
 * Convention lists drive Kotlin-authored rule beans and deterministic repair handlers. Profile
 * and severity fields stay present for the later profile migration stage; #381 deliberately keeps
 * runtime profile/severity decisions in `application.yml`.
 */
data class BpmnerLintConfig(
    val profile: String = "recommended",
    val severityOverrides: Map<String, String?> = emptyMap(),
    val discouragedLeadingVerbs: List<String> = listOf("handle", "manage", "process", "perform", "do"),
    val elementTypeWords: List<String> = listOf("activity", "process", "event"),
    val allowedAcronyms: List<String> = listOf("BPMN", "ACME", "SLA", "API", "IT"),
    val technicalTokens: List<String> = listOf("api", "svc", "tbl", "req", "resp", "tmp", "proc", "obj"),
    val discouragedBpmnTypes: List<String> = listOf(
        "bpmn:Choreography",
        "bpmn:ChoreographyTask",
        "bpmn:SubChoreography",
        "bpmn:CallChoreography",
        "bpmn:Conversation",
        "bpmn:ConversationLink",
        "bpmn:ConversationAssociation",
        "bpmn:Transaction",
        "bpmn:CompensateEventDefinition",
        "bpmn:EscalationEventDefinition",
    ),
)
