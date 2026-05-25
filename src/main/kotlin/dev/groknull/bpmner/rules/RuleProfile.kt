/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules

import dev.groknull.bpmner.api.DiagnosticCode
import dev.groknull.bpmner.api.RuleSeverity

/**
 * Configuration shape that controls which diagnostics are surfaced and at what severity.
 *
 * **Shape only in Phase 1.** This data class declares the contract that Phase 2's
 * `application.yml` config loader and severity-override pipeline will fill in. The
 * [RuleEngine] does **not** consult `RuleProfile` yet — every rule's diagnostic flows
 * through unchanged in the current implementation. The override hook arrives with the
 * profile-resolution work in Phase 2.
 *
 * @property name Profile identifier (e.g. `"recommended"`, `"strict"`).
 * @property enabledDiagnosticCodes Which diagnostic codes are surfaced. The Phase 2 filter
 *   treats an **empty set as "allow all codes"** — the no-op identity that lets a default
 *   YAML config or a unit test omit the field without silently producing a phantom-passing
 *   evaluation. To suppress every diagnostic explicitly, callers must pass a sentinel
 *   non-empty set whose codes intentionally do not match anything emitted by the active
 *   rules. Codes that **are** listed are surfaced; codes absent from a non-empty set are
 *   dropped.
 * @property severityOverrides Per-code severity adjustments applied on top of each rule's
 *   declared severity. Codes not in the map keep the rule-declared severity.
 */
data class RuleProfile(
    val name: String,
    val enabledDiagnosticCodes: Set<DiagnosticCode>,
    val severityOverrides: Map<DiagnosticCode, RuleSeverity>,
)
