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
 *
 * Populated by Phase 1D (#212).
 */
@ApplicationModule(allowedDependencies = ["api"])
internal object RulesModule
