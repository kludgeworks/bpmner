/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.api

/**
 * Metadata describing a BPMN validation rule, aligned with the
 * [BpmnRule.pkl](linter/pkl/schema/BpmnRule.pkl) schema.
 *
 * This structure will be Pkl-codegen'd in Phase 2. For now it is a hand-maintained
 * Kotlin data class that mirrors the Pkl schema closely enough that Phase 2 can
 * replace it without discarding the shape.
 */
data class RuleMetadata(
    val id: String,
    val name: String,
    val slug: String,
    val category: String,
    val intent: String,
    val forModellers: String,
    val forAI: String,
    val targetElements: List<String>,
    val severity: RuleSeverity = RuleSeverity.WARNING,
    val errorMessages: Map<String, String>,
    val repair: RepairMetadata = RepairMetadata(),
    val staticConfig: Map<String, Any>? = null,
    /** Kotlin class name of the compiled rule implementation, or null if not yet ported. */
    val implementation: String? = null,
    val aliases: List<String> = emptyList(),
    val deprecated: Boolean = false,
    val replacedBy: List<String> = emptyList(),
    val deprecationReason: String? = null,
)

/**
 * Repair strategy metadata for a rule, matching the `Repair` class in `BpmnRule.pkl`.
 */
data class RepairMetadata(
    val kind: String = "LLM_MODEL_PATCH",
    val safety: String = "LLM_ONLY",
    val handler: String? = null,
    val replacementMap: Map<String, String>? = null,
)
