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
 * - The LLM rule agent in `internal/adapter/inbound/` — a GOAP-shaped `@Agent` that
 *   evaluates `LlmCheckRule`-typed Pkl rules via Embabel's `PromptRunner`. This is why
 *   the module depends on `core` (for `BpmnConfig.linter`) in addition to `api`.
 *
 * Populated by Phase 1D (#212) and Phase 2 work (#239, #240).
 */
@ApplicationModule(allowedDependencies = ["api", "core", "pkl"])
internal object RulesModule
