/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.api

/**
 * Metadata describing a BPMN validation rule.
 *
 * This structure contains a `RepairMetadata` instance via its `repair` field
 * that rules use to declare their repair behavior.
 */
data class RuleMetadata(
    val id: String,
    val name: String,
    val slug: String,
    val category: RuleCategory,
    val intent: String,
    val forModellers: String,
    val forAI: String,
    val targetElements: List<String>,
    val errorMessages: Map<String, String>,
    val severity: RuleSeverity = RuleSeverity.WARNING,
    val repair: RepairMetadata = RepairMetadata(),
    val aliases: List<String> = emptyList(),
    val deprecated: Boolean = false,
    val replacedBy: List<String> = emptyList(),
    val deprecationReason: String? = null,
) {
    init {
        require(errorMessages.isNotEmpty()) { "Rule '$id' must define at least one error message" }
    }
}

/**
 * Repair strategy metadata for a rule.
 */
data class RepairMetadata(
    val kind: RepairKind = RepairKind.LLM_MODEL_PATCH,
    val safety: RepairSafety = RepairSafety.LLM_ONLY,
    val handler: String? = null,
    val replacementMap: Map<String, String>? = null,
)
