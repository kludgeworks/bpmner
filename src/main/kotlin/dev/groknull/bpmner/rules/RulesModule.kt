/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules

import org.springframework.modulith.ApplicationModule

/**
 * Rule engine module — orchestrates evaluation of
 * [dev.groknull.bpmner.api.BpmnRule] implementations against a `BpmnDefinition`.
 *
 * This package is the home for:
 * - `RuleEngine` primary port
 * - `RuleRegistry` for rule discovery
 * - `RuleProfile` for severity overrides
 * - Default implementations in `internal/domain/`
 * - LLM rule metadata specs (`LlmRuleSpec`) for guidance generation.
 *
 * Populated by Phase 1D (#212) and Phase 2 work (#239, #240, #380).
 */
@ApplicationModule(allowedDependencies = ["api", "config", "domain"])
internal object RulesModule
