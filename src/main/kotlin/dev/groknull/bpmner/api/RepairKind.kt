/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.api

/**
 * Classification of how a [RuleDiagnostic] (or legacy `validation.BpmnDiagnostic`) may be
 * repaired:
 *
 * - [LOCAL_MODEL_FIX] — deterministic, in-process repair handler.
 * - [LOCAL_XML_FIX] — deprecated; collapsed into [LOCAL_MODEL_FIX] in Phase 2F (#243).
 * - [LLM_MODEL_PATCH] / [LLM_XML_REWRITE] — repair requires the language model.
 * - [UNFIXABLE] — the diagnostic must be surfaced to the user; no automated repair applies.
 *
 * Mirrors the legacy Pkl schema. All rules are now implemented as Kotlin bean classes
 * registered in `BeanRuleRegistry`.
 */
enum class RepairKind {
    LOCAL_MODEL_FIX,

    @Deprecated(
        message = "Collapsed into LOCAL_MODEL_FIX in Phase 2F (#243). Delete in Phase 3 once " +
            "PklRuleCapabilityAdapter no longer round-trips the legacy string.",
        replaceWith = ReplaceWith("LOCAL_MODEL_FIX"),
    )
    LOCAL_XML_FIX,
    LLM_MODEL_PATCH,
    LLM_XML_REWRITE,
    UNFIXABLE,
    ;

    @Suppress("DEPRECATION") // TODO(Phase 3): drop the LOCAL_XML_FIX branch with the enum value
    fun isLocal(): Boolean = this == LOCAL_MODEL_FIX || this == LOCAL_XML_FIX

    fun isLlm(): Boolean = this == LLM_MODEL_PATCH || this == LLM_XML_REWRITE
}
