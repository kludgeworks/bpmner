/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain

import dev.groknull.bpmner.api.BpmnRule
import dev.groknull.bpmner.rules.RuleRegistry
import dev.groknull.bpmner.rules.internal.domain.mapping.BpmnRuleAdapter
import dev.groknull.bpmner.rules.internal.domain.mapping.MappedCheck
import dev.groknull.bpmner.rules.internal.domain.nlp.BpmnNlp
import org.pkl.config.java.ConfigEvaluator
import org.pkl.config.kotlin.forKotlin
import org.pkl.config.kotlin.to
import org.pkl.core.ModuleSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
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
 *  - **Deterministic** — wrapped in [DeterministicRule], evaluated via primitive sub-checks
 *    on the normal `RuleEngine` call.
 *  - **Composite** — wrapped in [CompositeRule], delegates to composite evaluation.
 *
 * Rules with `checkPrimitive == null` (the ~15 deferred awaiting #196) or
 * `severity == "off"` are filtered out by [BpmnRuleAdapter.adapt] and logged.
 *
 * Compiled rules win on duplicate id: a Pkl rule whose id matches a compiled rule is
 * dropped with a warning. This is the contract from #241.3.
 */
@Component
@ConditionalOnProperty(name = ["bpmner.rules.source"], havingValue = "pkl", matchIfMissing = true)
internal class PklRuleCatalog(
    compiledRules: List<BpmnRule>,
    private val nlp: BpmnNlp,
    @Value("\${bpmner.rules.indexUri:${RULES_INDEX_URI}}")
    private val rulesIndexUri: String = RULES_INDEX_URI,
) : RuleRegistry {
    private val logger = LoggerFactory.getLogger(PklRuleCatalog::class.java)

    private val allRules: List<BpmnRule>
    private val byId: Map<String, BpmnRule>
    private val byAlias: Map<String, BpmnRule>

    init {
        val pklBpmn = loadPklRules()
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

        allRules = compiledRules + pklDeduped
        byId = allRules.associateBy { it.id }
        // Build the alias index — only entries that don't collide with canonical ids.
        // Last-write-wins on duplicate aliases between rules; ids always take precedence
        // via `ruleByIdOrAlias`'s `byId.orElse(byAlias)` fallback ordering.
        byAlias = allRules
            .flatMap { rule -> rule.metadata.aliases.map { alias -> alias to rule } }
            .filter { (alias, _) -> alias !in byId }
            .toMap()

        logger.info(
            "PklRuleCatalog loaded: {} compiled + {} pkl deterministic",
            compiledRules.size,
            pklDeduped.size,
        )
    }

    override fun activeRules(): List<BpmnRule> = allRules

    override fun ruleById(id: String): BpmnRule? = byId[id]

    override fun ruleByIdOrAlias(id: String): BpmnRule? = byId[id] ?: byAlias[id]

    private fun loadPklRules(): List<BpmnRule> {
        val rules = evaluatePklRules()
        val bpmn = mutableListOf<BpmnRule>()
        var skipped = 0
        for (generated in rules) {
            val adapted = BpmnRuleAdapter.adapt(generated)
            if (adapted == null) {
                skipped++
                continue
            }
            when (val mc = adapted.mappedCheck) {
                is MappedCheck.Deterministic ->
                    bpmn += DeterministicRule(adapted.metadata, mc.config, nlp)

                is MappedCheck.Composite ->
                    bpmn += CompositeRule(adapted.metadata, mc.config, nlp)
            }
        }
        if (skipped > 0) {
            logger.info(
                "Skipped {} pkl rule(s) with no checkPrimitive or severity=off (awaiting #196 or disabled)",
                skipped,
            )
        }
        return bpmn
    }

    private fun evaluatePklRules(): List<PklBpmnRule> {
        ConfigEvaluator.preconfigured().forKotlin().use { evaluator ->
            val config = evaluator.evaluate(ModuleSource.uri(URI.create(rulesIndexUri)))
            return config.get("rules").to()
        }
    }

    companion object {
        const val RULES_INDEX_URI = "modulepath:/linter/pkl/RulesIndex.pkl"
    }
}
