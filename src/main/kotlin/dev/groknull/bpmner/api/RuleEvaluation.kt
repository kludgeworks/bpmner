/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.api

/**
 * Result of evaluating all active [BpmnRule]s against a single `BpmnDefinition`.
 *
 * [passed] is derived — it is `true` when no [RuleSeverity.ERROR] diagnostics remain.
 * Advisory diagnostics ([RuleSeverity.WARNING] / [RuleSeverity.INFO]) may persist in
 * a passing evaluation.
 */
data class RuleEvaluation(
    val diagnostics: List<RuleDiagnostic>,
) {
    /** ERROR-severity diagnostics — the ones the refinement engine must drive to zero. */
    val blockingDiagnostics: List<RuleDiagnostic> =
        diagnostics.filter { it.severity == RuleSeverity.ERROR }

    /** WARNING / INFO diagnostics — surfaced in the final report but not blocking. */
    val advisoryDiagnostics: List<RuleDiagnostic> =
        diagnostics.filterNot { it.severity == RuleSeverity.ERROR }

    /** True when no blocking (ERROR) diagnostics remain. */
    val passed: Boolean = blockingDiagnostics.isEmpty()
}
