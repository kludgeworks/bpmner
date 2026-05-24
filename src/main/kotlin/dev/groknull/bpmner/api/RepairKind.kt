/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.api

/**
 * Classification of how a [RuleDiagnostic] (or legacy `validation.BpmnDiagnostic`) may be
 * repaired:
 *
 * - [LOCAL_MODEL_FIX] / [LOCAL_XML_FIX] — deterministic, in-process repair handler.
 * - [LLM_MODEL_PATCH] / [LLM_XML_REWRITE] — repair requires the language model.
 * - [UNFIXABLE] — the diagnostic must be surfaced to the user; no automated repair applies.
 *
 * Mirrors the `RepairKind` typealias in [BpmnRule.pkl](linter/pkl/schema/BpmnRule.pkl) so that
 * Pkl-authored rule metadata round-trips into Kotlin via `RepairKind.valueOf(...)`.
 */
enum class RepairKind {
    LOCAL_MODEL_FIX,
    LOCAL_XML_FIX,
    LLM_MODEL_PATCH,
    LLM_XML_REWRITE,
    UNFIXABLE,
    ;

    fun isLocal(): Boolean = this == LOCAL_MODEL_FIX || this == LOCAL_XML_FIX

    fun isLlm(): Boolean = this == LLM_MODEL_PATCH || this == LLM_XML_REWRITE
}
