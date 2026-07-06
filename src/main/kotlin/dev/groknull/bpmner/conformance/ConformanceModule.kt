/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.conformance

import org.springframework.modulith.ApplicationModule

/**
 * Conformance module — XSD validation and rule evaluation. Public ports: [BpmnValidator]
 * (use-case), [BpmnLintingPort], [BpmnXsdValidationPort], [BpmnRuleGuidancePort]. The concrete
 * implementations (`BpmnEvaluationPipeline`, `BpmnDiagnosticNormalizer`, `BpmnXsdValidator`,
 * `BpmnDefinitionValidator`, and the `internal/adapter/outbound/RuleEngineLintingAdapter` backing
 * `BpmnLintingPort`) are `internal` and not part of the cross-module surface.
 */
@ApplicationModule
internal object ConformanceModule
