/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.validation

import org.springframework.modulith.ApplicationModule

/**
 * Validation module — XSD validation, linting, rule-catalog adaptation, and the
 * legacy BpmnDefinitionValidator (kept alongside the new compiled rules for the
 * #216 parity test). Public surface: BpmnLintService, BpmnXsdValidator,
 * BpmnDefinitionValidator, BpmnDiagnosticNormalizer, BpmnEvaluationPipeline,
 * PklRuleCapabilityAdapter, RuleCatalogService, BpmnLintJsEngine.
 */
@ApplicationModule(allowedDependencies = ["api", "core"])
internal object ValidationModule
