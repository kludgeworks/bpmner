/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset

/**
 * Modelling-team lint configuration loaded from the packaged `bpmner.pkl` template at startup
 * by [dev.groknull.bpmner.ruleset.internal.config.ConventionsLoader].
 *
 * [profile] selects the built-in rule profile (`recommended` or `strict`); [severityOverrides]
 * provides per-rule severity adjustments on top of the profile. Both are consumed by
 * [dev.groknull.bpmner.ruleset.internal.domain.RuleProfileFactory] to produce the active
 * [dev.groknull.bpmner.ruleset.RuleProfile] at startup.
 *
 * The convention lists ([discouragedLeadingVerbs], [elementTypeWords], [allowedAcronyms],
 * [discouragedBpmnTypes]) drive Kotlin-authored rule beans and deterministic repair handlers.
 */
data class BpmnerLintConfig(
    val profile: String = "recommended",
    val severityOverrides: Map<String, String?> = emptyMap(),
    val discouragedLeadingVerbs: List<String> = listOf("handle", "manage", "process", "perform", "do"),
    val elementTypeWords: List<String> = listOf("activity", "process", "event"),
    val allowedAcronyms: List<String> = listOf("BPMN", "ACME", "SLA", "API", "IT"),
    val discouragedBpmnTypes: List<String> = listOf(
        "bpmn:Choreography",
        "bpmn:ChoreographyTask",
        "bpmn:SubChoreography",
        "bpmn:CallChoreography",
        "bpmn:Conversation",
        "bpmn:ConversationLink",
        "bpmn:ConversationAssociation",
        "bpmn:Transaction",
        "bpmn:DataObject",
        "bpmn:DataObjectReference",
        "bpmn:DataStore",
        "bpmn:DataStoreReference",
        "bpmn:DataInputAssociation",
        "bpmn:DataOutputAssociation",
    ),
    val abbreviationReplacements: Map<String, String> = mapOf(
        "REQ" to "request",
        "RESP" to "response",
        "AUTH" to "authentication",
        "CFG" to "configuration",
        "MSG" to "message",
        "DOC" to "document",
        "ITBL" to "itinerary block",
    ),
)
