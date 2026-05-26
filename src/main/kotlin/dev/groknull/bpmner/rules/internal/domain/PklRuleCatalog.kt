/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain

import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.BpmnRule
import dev.groknull.bpmner.api.RuleDiagnostic
import dev.groknull.bpmner.api.RuleMetadata
import dev.groknull.bpmner.rules.LlmRuleSpec
import dev.groknull.bpmner.rules.RuleRegistry
import dev.groknull.bpmner.rules.internal.domain.mapping.BpmnRuleAdapter
import dev.groknull.bpmner.rules.internal.domain.mapping.MappedCheck
import dev.groknull.bpmner.rules.internal.domain.primitives.CompositeCheck
import dev.groknull.bpmner.rules.internal.domain.primitives.CompositeCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.DeterministicCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.SubCheckEvaluator
import dev.groknull.bpmner.rules.internal.domain.primitives.toPrimitiveModelContext
import org.pkl.config.java.ConfigEvaluator
import org.pkl.config.kotlin.forKotlin
import org.pkl.config.kotlin.to
import org.pkl.core.ModuleSource
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import dev.groknull.bpmner.pkl.BpmnRule as PklBpmnRule

/**
 * Loads the Pkl rule catalog at startup, merges it with compiled `@Component BpmnRule` beans,
 * and exposes the result through [RuleRegistry]. Replaces the previous JSON-resource path
 * (`RuleCatalogService` + `linter-rules.json`) and absorbs `InMemoryRuleRegistry`.
 *
 * Layout in the fat JAR (see `linter/pkl:pkl_runtime_resources`):
 * ```
 * linter/pkl/RulesIndex.pkl       entry point with explicit imports of every rule
 * linter/pkl/rules/{name}.pkl     rule modules
 * linter/pkl/schema/{name}.pkl    shared schema (BpmnRule, CheckPrimitive, RuleCategory)
 * ```
 *
 * Three rule families come out of one Pkl evaluation:
 *  - **Deterministic** — wrapped in [DeterministicPklRule], evaluated via [SubCheckEvaluator]
 *    on the normal `RuleEngine` call.
 *  - **Composite** — wrapped in [CompositePklRule], delegates to [CompositeCheck].
 *  - **LLM** — wrapped in [LlmPklRule] with a no-op evaluate (the rule shows up in the
 *    registry so tooling can enumerate it), plus surfaced separately through
 *    [llmRuleSpecs] so a caller can build an `LlmRuleEvaluationRequest` for `LlmRuleAgent`.
 *
 * Rules with `checkPrimitive == null` (the ~15 deferred awaiting #196) or
 * `severity == "off"` are filtered out by [BpmnRuleAdapter.adapt] and logged.
 *
 * Compiled rules win on duplicate id: a Pkl rule whose id matches a compiled rule is
 * dropped with a warning. This is the contract from #241.3.
 */
@Component
internal class PklRuleCatalog(
    compiledRules: List<BpmnRule>,
    rulesIndexUri: String = RULES_INDEX_URI,
) : RuleRegistry {
    private val logger = LoggerFactory.getLogger(PklRuleCatalog::class.java)

    private val rulesIndexUri: String = rulesIndexUri
    private val allRules: List<BpmnRule>
    private val byId: Map<String, BpmnRule>
    private val llmSpecs: List<LlmRuleSpec>

    init {
        val (pklBpmn, pklLlm) = loadPklRules()
        val compiledIds = compiledRules.map { it.id }.toSet()
        val pklDeduped = pklBpmn.filterNot { rule ->
            val shadowed = rule.id in compiledIds
            if (shadowed) {
                logger.warn(
                    "Pkl rule '{}' shadowed by compiled BpmnRule of the same id — Pkl version skipped",
                    rule.id,
                )
            }
            shadowed
        }
        val llmDeduped = pklLlm.filterNot { spec ->
            val shadowed = spec.metadata.id in compiledIds
            if (shadowed) {
                logger.warn(
                    "Pkl LLM rule '{}' shadowed by compiled BpmnRule of the same id — LLM spec dropped",
                    spec.metadata.id,
                )
            }
            shadowed
        }

        allRules = compiledRules + pklDeduped
        byId = allRules.associateBy { it.id }
        llmSpecs = llmDeduped

        logger.info(
            "PklRuleCatalog loaded: {} compiled + {} pkl deterministic + {} llm spec(s)",
            compiledRules.size,
            pklDeduped.size,
            llmSpecs.size,
        )
    }

    override fun activeRules(): List<BpmnRule> = allRules

    override fun ruleById(id: String): BpmnRule? = byId[id]

    /**
     * Pkl-loaded rules whose `checkPrimitive == "LlmCheckRule"`. Consumers (e.g. a pipeline
     * step that wants to invoke `LlmRuleAgent`) build an `LlmRuleEvaluationRequest` from this
     * list. The deterministic engine path does not run these specs.
     */
    fun llmRuleSpecs(): List<LlmRuleSpec> = llmSpecs

    private fun loadPklRules(): Pair<List<BpmnRule>, List<LlmRuleSpec>> {
        val rules = evaluatePklRules()
        val bpmn = mutableListOf<BpmnRule>()
        val llm = mutableListOf<LlmRuleSpec>()
        var skipped = 0
        for (generated in rules) {
            val adapted = BpmnRuleAdapter.adapt(generated)
            if (adapted == null) {
                skipped++
                continue
            }
            when (val mc = adapted.mappedCheck) {
                is MappedCheck.Deterministic ->
                    bpmn += DeterministicPklRule(adapted.metadata, mc.config)
                is MappedCheck.Composite ->
                    bpmn += CompositePklRule(adapted.metadata, mc.config)
                is MappedCheck.Llm -> {
                    bpmn += LlmPklRule(adapted.metadata)
                    llm += LlmRuleSpec(adapted.metadata, mc.config)
                }
            }
        }
        if (skipped > 0) {
            logger.info(
                "Skipped {} pkl rule(s) with no checkPrimitive or severity=off (awaiting #196 or disabled)",
                skipped,
            )
        }
        return bpmn to llm
    }

    private fun evaluatePklRules(): List<PklBpmnRule> {
        ConfigEvaluator.preconfigured().forKotlin().use { evaluator ->
            val config = evaluator.evaluate(ModuleSource.uri(URI(rulesIndexUri)))
            return config.get("rules").to()
        }
    }

    companion object {
        const val RULES_INDEX_URI = "modulepath:/linter/pkl/RulesIndex.pkl"
    }
}

private class DeterministicPklRule(
    override val metadata: RuleMetadata,
    private val config: DeterministicCheckConfig,
) : BpmnRule {
    override val id: String = metadata.id

    override fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic> = SubCheckEvaluator.evaluate(
        ctx.toPrimitiveModelContext(),
        metadata,
        config,
    )
}

private class CompositePklRule(
    override val metadata: RuleMetadata,
    private val config: CompositeCheckConfig,
) : BpmnRule {
    override val id: String = metadata.id

    override fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic> = CompositeCheck().evaluate(ctx, metadata, config)
}

private class LlmPklRule(
    override val metadata: RuleMetadata,
) : BpmnRule {
    override val id: String = metadata.id

    // LlmCheckRule rules are evaluated outside the deterministic RuleEngine via LlmRuleAgent —
    // see [PklRuleCatalog.llmRuleSpecs]. The wrapper still lives in the registry so tooling
    // that enumerates "all rules" sees the LLM ones too.
    override fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic> = emptyList()
}
