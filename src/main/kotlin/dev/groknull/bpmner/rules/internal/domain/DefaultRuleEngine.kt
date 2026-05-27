/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain

import dev.groknull.bpmner.api.BpmnDefinition
import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.BpmnRule
import dev.groknull.bpmner.api.RuleDiagnostic
import dev.groknull.bpmner.api.RuleEvaluation
import dev.groknull.bpmner.api.RuleSeverity
import dev.groknull.bpmner.rules.RuleEngine
import dev.groknull.bpmner.rules.RuleProfile
import dev.groknull.bpmner.rules.RuleRegistry
import org.jmolecules.ddd.annotation.Service
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Default [RuleEngine] implementation. Wraps the [BpmnDefinition] in a
 * [BpmnDefinitionContext] once per call (so every rule reuses the same pre-computed
 * indexes), iterates [RuleRegistry.activeRules], and flattens every emitted diagnostic
 * into a single [RuleEvaluation].
 *
 * **Rule-exception isolation:** each [BpmnRule.evaluate] call is wrapped in
 * `runCatching`. A rule that throws does not abort the evaluation — its failure is
 * converted to an ERROR-severity [RuleDiagnostic] (code `rule-execution-failure`), and
 * the engine continues with the next rule. This matches the Tier-3 plugin contract
 * where third-party rules cannot be trusted to handle every BPMN shape.
 *
 * The engine consults [RuleProfile] in two places:
 *  1. **Rule filtering** — rules in [RuleProfile.disabledRuleIds] are skipped before
 *     evaluation. They consume no work and emit no diagnostics. This is the runtime
 *     equivalent of `severity = "off"` in the pre-2G `bpmner.lint.rules` YAML.
 *  2. **Severity override** — diagnostics whose `ruleId` matches a key in
 *     [RuleProfile.severityOverrides] are rewritten with the new severity. The override
 *     applies to every diagnostic the rule emits (consistent with Pkl's rule-level
 *     `severity` field — finer granularity is a #221 concern).
 *
 * [RuleProfile.EMPTY] is the identity: neither hook fires, so the engine behaves exactly
 * as the pre-Phase-2E pass-through.
 */
@Service
@Component
internal class DefaultRuleEngine(
    private val registry: RuleRegistry,
    private val profile: RuleProfile,
) : RuleEngine {
    private val logger = LoggerFactory.getLogger(DefaultRuleEngine::class.java)

    override fun evaluate(definition: BpmnDefinition): RuleEvaluation {
        val ctx = BpmnDefinitionContext(definition)
        val diagnostics =
            registry.activeRules()
                .filterNot { profile.isDisabled(it.id) }
                .flatMap { rule ->
                    runCatching { rule.evaluate(ctx) }
                        .getOrElse { failure -> ruleFailureDiagnostic(rule, failure) }
                }
                .map { d ->
                    profile.overriddenSeverity(d.ruleId)
                        ?.let { newSeverity -> d.copy(severity = newSeverity) }
                        ?: d
                }
        return RuleEvaluation(diagnostics)
    }

    private fun ruleFailureDiagnostic(
        rule: BpmnRule,
        failure: Throwable,
    ): List<RuleDiagnostic> {
        logger.error("Rule '{}' threw while evaluating definition", rule.id, failure)
        return listOf(
            RuleDiagnostic(
                diagnosticCode = RULE_EXECUTION_FAILURE_CODE,
                ruleId = rule.id,
                severity = RuleSeverity.ERROR,
                message = "Rule '${rule.id}' threw ${failure::class.simpleName}: ${failure.message}",
            ),
        )
    }

    private companion object {
        const val RULE_EXECUTION_FAILURE_CODE: String = "rule-execution-failure"
    }
}
