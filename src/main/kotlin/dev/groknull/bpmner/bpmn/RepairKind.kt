/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.bpmn

/**
 * Classification of how a [RuleDiagnostic] (or legacy `validation.BpmnDiagnostic`) may be
 * repaired:
 *
 * - [LOCAL_MODEL_FIX] — deterministic, in-process repair handler.
 * - [LOCAL_XML_FIX] — deprecated; collapsed into [LOCAL_MODEL_FIX].
 * - [LLM_MODEL_PATCH] / [LLM_XML_REWRITE] — repair requires the language model.
 * - [UNFIXABLE] — the diagnostic must be surfaced to the user; no automated repair applies.
 *
 * Rules declare their repair strategy via `RepairMetadata` in their Kotlin bean config.
 */
enum class RepairKind {
    LOCAL_MODEL_FIX,

    @Deprecated(
        message = "Collapsed into LOCAL_MODEL_FIX. Delete in Phase 3 once legacy consumers are gone.",
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
