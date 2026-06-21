/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset

import org.springframework.modulith.ApplicationModule

/**
 * Ruleset module — orchestrates evaluation of
 * [dev.groknull.bpmner.bpmn.BpmnRule] implementations against a `BpmnDefinition`.
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
@ApplicationModule(allowedDependencies = ["bpmn", "config"])
internal object RulesetModule
