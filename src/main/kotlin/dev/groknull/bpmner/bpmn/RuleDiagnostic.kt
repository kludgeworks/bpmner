/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.bpmn

/**
 * A single diagnostic emitted by a [BpmnRule] during evaluation.
 *
 * Each diagnostic carries a [diagnosticCode] that uniquely identifies the specific
 * check that fired (e.g. `"def-duplicate-node-id"`). Severity overrides and repair
 * dispositions key on [diagnosticCode], not [ruleId], because a single rule may
 * emit multiple distinct codes.
 *
 * @property diagnosticCode Unique code identifying the specific check (e.g. `"def-dangling-source"`).
 * @property ruleId The [BpmnRule.id] of the rule that produced this diagnostic.
 * @property severity The severity level of this diagnostic.
 * @property message Human-readable description of the issue.
 * @property elementId Optional BPMN element ID where the issue was detected.
 */
data class RuleDiagnostic(
    val diagnosticCode: DiagnosticCode,
    val ruleId: String,
    val severity: RuleSeverity,
    val message: String,
    val elementId: String? = null,
)
