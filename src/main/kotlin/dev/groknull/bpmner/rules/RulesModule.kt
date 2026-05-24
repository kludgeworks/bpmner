/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules

/**
 * Rule engine module — orchestrates evaluation of
 * [dev.groknull.bpmner.api.BpmnRule] implementations against a `BpmnDefinition`.
 *
 * This package is the home for:
 * - `RuleEngine` primary port
 * - `RuleRegistry` for rule discovery
 * - `RuleProfile` for severity overrides
 * - Default implementations in `internal/domain/`
 *
 * Populated by Phase 1D (#212). This marker object ensures Spring Modulith
 * detects the module by convention.
 */
internal object RulesModule
