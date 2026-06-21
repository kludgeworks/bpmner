/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset.internal.domain

import dev.groknull.bpmner.bpmn.BpmnDefinitionContext
import dev.groknull.bpmner.bpmn.BpmnRule
import dev.groknull.bpmner.bpmn.RuleDiagnostic
import dev.groknull.bpmner.bpmn.RuleMetadata
import dev.groknull.bpmner.ruleset.internal.domain.nlp.BpmnNlp
import dev.groknull.bpmner.ruleset.internal.domain.primitives.CompositeCheck
import dev.groknull.bpmner.ruleset.internal.domain.primitives.CompositeCheckConfig
import dev.groknull.bpmner.ruleset.internal.domain.primitives.DeterministicCheckConfig
import dev.groknull.bpmner.ruleset.internal.domain.primitives.SubCheckEvaluator
import dev.groknull.bpmner.ruleset.internal.domain.primitives.toPrimitiveModelContext

internal class DeterministicRule(
    override val metadata: RuleMetadata,
    internal val config: DeterministicCheckConfig,
    private val nlp: BpmnNlp,
) : BpmnRule {
    override val id: String = metadata.id

    override fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic> = evaluateDeterministic(ctx, metadata, config, nlp)
}

internal class CompositeRule(
    override val metadata: RuleMetadata,
    internal val config: CompositeCheckConfig,
    private val nlp: BpmnNlp,
) : BpmnRule {
    override val id: String = metadata.id

    override fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic> = evaluateComposite(ctx, metadata, config, nlp)
}

internal fun evaluateDeterministic(
    ctx: BpmnDefinitionContext,
    metadata: RuleMetadata,
    config: DeterministicCheckConfig,
    nlp: BpmnNlp,
): List<RuleDiagnostic> = SubCheckEvaluator.evaluate(
    ctx.toPrimitiveModelContext(),
    metadata,
    config,
    nlp,
)

internal fun evaluateComposite(
    ctx: BpmnDefinitionContext,
    metadata: RuleMetadata,
    config: CompositeCheckConfig,
    nlp: BpmnNlp,
): List<RuleDiagnostic> = CompositeCheck.evaluate(ctx, metadata, config, nlp)
