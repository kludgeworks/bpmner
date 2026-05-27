/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain

import dev.groknull.bpmner.api.RepairKind
import dev.groknull.bpmner.repair.BpmnLocalFixSummary
import dev.groknull.bpmner.repair.BpmnRepairAttempt
import dev.groknull.bpmner.rules.RuleRegistry
import dev.groknull.bpmner.validation.BpmnDiagnostic
import dev.groknull.bpmner.validation.BpmnLintRuleIds
import org.jmolecules.ddd.annotation.Service
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Applies `LOCAL_MODEL_FIX` repairs by dispatching diagnostics to their declared Kotlin handler.
 *
 * Until issue #243 closed the `LOCAL_XML_FIX` collapse, this strategy also routed
 * `LOCAL_XML_FIX`-flagged diagnostics to bpmnlint's TS auto-fixer. After 2A migrated the last
 * 6 rules to `LOCAL_MODEL_FIX`, that branch became dead code and is gone. After #241 2G,
 * the per-rule handler config is sourced from [RuleRegistry] (PklRuleCatalog) directly —
 * `RuleCatalogService` and the bpmnlint JS bridge are removed.
 *
 * Per-rule handler config (e.g. `discouragedWords`, `replacementMap`) is projected from
 * [RuleRegistry.ruleByIdOrAlias] metadata into a [HandlerConfig] before dispatch — so the
 * Pkl rule remains the single source of truth for the handler's data inputs.
 */
@Service
@Component
internal class DeterministicTopologyRepairStrategy(
    private val modelFixHandlerRegistry: BpmnLocalModelFixHandlerRegistry,
    private val patchApplier: BpmnPatchApplicationPort,
    private val ruleRegistry: RuleRegistry,
) : BpmnRepairStrategy {
    private val logger = LoggerFactory.getLogger(DeterministicTopologyRepairStrategy::class.java)

    override fun getOrder(): Int = 75

    override fun repair(context: BpmnRepairStrategyContext): BpmnRepairResult {
        val attempt = context.attempt
        attempt.diagnostics.forEach { diagnostic ->
            val repaired = applyLocalModelFix(attempt, diagnostic)
            if (repaired != null) return repaired
        }
        return BpmnRepairResult.NotApplicable
    }

    private fun applyLocalModelFix(
        attempt: BpmnRepairAttempt,
        diagnostic: BpmnDiagnostic,
    ): BpmnRepairResult.Repaired? {
        if (diagnostic.kind != RepairKind.LOCAL_MODEL_FIX) return null
        val handlerName = diagnostic.fixHandler ?: return null
        val elementId = diagnostic.elementId ?: return null
        val handler = modelFixHandlerRegistry.lookup(handlerName) ?: return null
        val config = handlerConfigFor(diagnostic)
        val ops = handler.buildPatch(attempt.definition, elementId, config)
        if (ops.isEmpty()) return null
        val patch =
            BpmnRepairPatch(
                operations = ops,
                reason = "LOCAL_MODEL_FIX: $handlerName on $elementId",
            )
        return when (val applied = patchApplier.apply(attempt.definition, patch)) {
            is PatchApplicationResult.Success -> {
                logger.info("Local model fix applied: handler={}, elementId={}", handlerName, elementId)
                BpmnRepairResult.Repaired(
                    definition = applied.definition,
                    promptText = patch.reason ?: "Local model fix",
                    messages = attempt.messages,
                    localFixSummary = BpmnLocalFixSummary(modelApplied = 1, xmlApplied = 0, xmlErrors = 0),
                )
            }

            is PatchApplicationResult.Failure -> {
                logger.warn(
                    "Local model fix produced invalid patch; falling through. handler={}, elementId={}, reason={}",
                    handlerName,
                    elementId,
                    applied.reason,
                )
                null
            }

            PatchApplicationResult.NoOp -> null
        }
    }

    private fun handlerConfigFor(diagnostic: BpmnDiagnostic): HandlerConfig {
        val ruleId = diagnostic.bareRuleId() ?: return HandlerConfig.EMPTY
        // BpmnRuleAdapter.staticConfigFromPkl already normalises the field to Map<String, Any>?
        // when the rule is loaded, so the previous defensive cast + type-check is unnecessary.
        val meta = ruleRegistry.ruleByIdOrAlias(ruleId)?.metadata ?: return HandlerConfig.EMPTY
        return HandlerConfig(
            staticConfig = meta.staticConfig,
            replacementMap = meta.repair.replacementMap,
        )
    }

    // Strips any legacy `bpmner/` prefix from rule ids. RuleEngine-sourced diagnostics already
    // carry bare ids and pass through unchanged; the helper survives one more commit because
    // it's needed for the rare case of an externally-supplied diagnostic that still carries the
    // prefix. Dies in #241 2G commit B with BpmnLintRuleIds itself.
    private fun BpmnDiagnostic.bareRuleId(): String? = rule?.let(BpmnLintRuleIds::bareRuleId)
}
