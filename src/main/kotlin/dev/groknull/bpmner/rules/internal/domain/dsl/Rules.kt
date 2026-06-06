/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.dsl

import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.BpmnRule
import dev.groknull.bpmner.api.RuleDiagnostic
import dev.groknull.bpmner.api.RuleMetadata
import dev.groknull.bpmner.rules.internal.domain.nlp.BpmnNlp
import dev.groknull.bpmner.rules.internal.domain.primitives.CompositeCheck
import dev.groknull.bpmner.rules.internal.domain.primitives.CompositeCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.DeterministicCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.SubCheckEvaluator
import dev.groknull.bpmner.rules.internal.domain.primitives.toPrimitiveModelContext

/*
 * The three [BpmnRule] shapes a Kotlin rule definition compiles down to. The DSL builders in
 * `RuleDsl.kt` construct them. They mirror the private `*PklRule` wrappers that still live in
 * `PklRuleCatalog` for the Pkl path; at the registry cutover (#380), `PklRuleCatalog` and those
 * private wrappers are deleted and these become the sole shapes. The shared `evaluate()` bodies
 * live here, once — not per rule.
 */

/** Deterministic rule: one typed [DeterministicCheckConfig] dispatched through [SubCheckEvaluator]. */
internal class DeterministicRule(
    override val metadata: RuleMetadata,
    private val config: DeterministicCheckConfig,
    private val nlp: BpmnNlp,
) : BpmnRule {
    override val id: String = metadata.id

    override fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic> = SubCheckEvaluator.evaluate(
        ctx.toPrimitiveModelContext(),
        metadata,
        config,
        nlp,
    )
}

/** Composite rule: named deterministic sub-checks evaluated together via [CompositeCheck]. */
internal class CompositeRule(
    override val metadata: RuleMetadata,
    private val config: CompositeCheckConfig,
    private val nlp: BpmnNlp,
) : BpmnRule {
    override val id: String = metadata.id

    override fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic> = CompositeCheck.evaluate(ctx, metadata, config, nlp)
}

/**
 * LLM rule registry wrapper — metadata only; [evaluate] is a no-op. LLM rules are evaluated
 * outside the deterministic engine via `LlmRuleAgent` (see `LlmRuleSpec`). The wrapper exists so
 * the registry can resolve LLM rules by id/alias (diagnostic-markdown rendering) and tooling can
 * enumerate "all rules". The DSL's `llmRule(...)` returns the `LlmRuleSpec`; the registry builds
 * this wrapper from the collected specs (#380).
 */
internal class LlmRule(
    override val metadata: RuleMetadata,
) : BpmnRule {
    override val id: String = metadata.id

    override fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic> = emptyList()
}
