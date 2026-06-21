/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.validation

import org.springframework.modulith.ApplicationModule

/**
 * Validation module — XSD validation, rule evaluation, and the legacy
 * `BpmnDefinitionValidator` (kept alongside the new compiled rules for the #216 parity
 * test). Public ports: [BpmnValidator] (use-case), [BpmnLintingPort],
 * [BpmnXsdValidationPort], [BpmnRuleGuidancePort]. The concrete implementations
 * (`BpmnEvaluationPipeline`, `BpmnDiagnosticNormalizer`, `BpmnXsdValidator`,
 * `BpmnDefinitionValidator`, and the `internal/adapter/outbound/RuleEngineLintingAdapter`
 * backing `BpmnLintingPort`) are `internal` and not part of the cross-module surface.
 *
 * The legacy GraalJS bpmn-lint bridge (`BpmnLintService`, `BpmnLintJsEngine`,
 * `RuleCatalogService`, `PklRuleCapabilityAdapter`) was removed in #244 (Phase 2G).
 * `BpmnLintingPort` and the `LintIssue` / `BpmnLintRuleCapability` data shapes are
 * kept for one more cycle so consumers don't churn; a rename pass is scheduled for the
 * follow-up phase. GraalJS now only lives inside `dev.groknull.bpmner.layout` (the BPMN
 * auto-layout JS bundle).
 */
@ApplicationModule(allowedDependencies = ["bpmn", "config", "rules"])
internal object ValidationModule
