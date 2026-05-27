/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules

import dev.groknull.bpmner.api.RuleSeverity

/**
 * Active rule profile consulted by [RuleEngine] during evaluation.
 *
 * Built from `bpmner.rules.severity-overrides` config by
 * [dev.groknull.bpmner.rules.internal.domain.RuleProfileFactory]. Each key is a bare
 * [dev.groknull.bpmner.api.BpmnRule.id] (e.g. `act-verb-object-name`). The engine
 * (see [dev.groknull.bpmner.rules.internal.domain.DefaultRuleEngine]):
 *  1. Skips disabled rules entirely — `filterNot { profile.isDisabled(it.id) }` before
 *     evaluation. Diagnostics from those rules never reach downstream consumers.
 *  2. Applies severity overrides per emitted diagnostic — every diagnostic whose `ruleId`
 *     matches a key in [severityOverrides] is rewritten with the new severity.
 *
 * Per-rule (not per-diagnostic-code) granularity is intentional for Phase 2E. A rule that
 * emits multiple [dev.groknull.bpmner.api.DiagnosticCode]s gets a single override for all
 * of them, matching the Pkl-side `severity: Severity` field which is also per-rule. The
 * per-diagnostic-code escape hatch is deferred to #221 (Phase 6 named profiles).
 *
 * Use [EMPTY] in tests and in any code path that constructs a [RuleEngine] without a real
 * config — both [isDisabled] and [overriddenSeverity] return their identity values, so the
 * engine behaves identically to the pre-Phase-2E pass-through.
 */
data class RuleProfile(
    val severityOverrides: Map<String, RuleSeverity>,
    val disabledRuleIds: Set<String>,
) {
    fun isDisabled(ruleId: String): Boolean = ruleId in disabledRuleIds

    fun overriddenSeverity(ruleId: String): RuleSeverity? = severityOverrides[ruleId]

    companion object {
        val EMPTY = RuleProfile(severityOverrides = emptyMap(), disabledRuleIds = emptySet())
    }
}
