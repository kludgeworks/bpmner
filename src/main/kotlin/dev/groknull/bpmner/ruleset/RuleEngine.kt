/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset

import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.RuleEvaluation
import org.jmolecules.architecture.hexagonal.PrimaryPort

/**
 * Primary inbound port for evaluating all active [dev.groknull.bpmner.api.BpmnRule]s
 * against a [BpmnDefinition]. Consumers (the refactored repair loop, validation pipeline,
 * etc.) call [evaluate] once per definition and receive a single aggregated
 * [RuleEvaluation] containing every diagnostic emitted.
 *
 * The default implementation ([dev.groknull.bpmner.ruleset.internal.domain.DefaultRuleEngine])
 * builds a single [dev.groknull.bpmner.api.BpmnDefinitionContext] per call and fans the
 * pre-computed indexes out to every rule, so a rule that needs `nodesById` is doing the
 * work the context has already done.
 */
@PrimaryPort
fun interface RuleEngine {
    fun evaluate(definition: BpmnDefinition): RuleEvaluation
}
