/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain

import dev.groknull.bpmner.api.RepairKind
import dev.groknull.bpmner.repair.BpmnLocalFixSummary
import dev.groknull.bpmner.repair.BpmnRepairAttempt
import dev.groknull.bpmner.validation.BpmnDiagnostic
import dev.groknull.bpmner.validation.BpmnLintRuleIds
import dev.groknull.bpmner.validation.RuleCatalogService
import org.jmolecules.ddd.annotation.Service
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Applies `LOCAL_MODEL_FIX` repairs by dispatching diagnostics to their declared Kotlin handler.
 *
 * Until issue #243 closed the `LOCAL_XML_FIX` collapse, this strategy also routed
 * `LOCAL_XML_FIX`-flagged diagnostics to bpmnlint's TS auto-fixer via [BpmnLintingPort.autoFix].
 * After 2A migrated the last 6 rules to `LOCAL_MODEL_FIX`, that branch became dead code and is
 * gone. `BpmnLintingPort` itself is deprecated in #217's 2G.
 *
 * Per-rule handler config (e.g. `discouragedWords`, `replacementMap`) is pulled from
 * [RuleCatalogService.getRule] and projected to a [HandlerConfig] before dispatch — so the Pkl
 * rule remains the single source of truth for the handler's data inputs.
 */
@Service
@Component
internal class DeterministicTopologyRepairStrategy(
    private val modelFixHandlerRegistry: BpmnLocalModelFixHandlerRegistry,
    private val patchApplier: BpmnPatchApplicationPort,
    private val ruleCatalogService: RuleCatalogService,
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
        val meta = ruleCatalogService.getRule(ruleId) ?: return HandlerConfig.EMPTY

        @Suppress("UNCHECKED_CAST")
        val staticConfig = meta.staticConfig as? Map<String, Any>
        if (meta.staticConfig != null && staticConfig == null) {
            // A non-null `staticConfig` that isn't a Map silently produces an empty
            // HandlerConfig and the handler emits no patches — looks identical to "rule
            // satisfied" in production. Surface the misconfiguration so it can be fixed in
            // the Pkl rule rather than chased through dispatch logs.
            logger.warn(
                "Rule '{}' staticConfig has unexpected type {}; handler config will be empty",
                ruleId,
                meta.staticConfig!!::class.simpleName,
            )
        }
        return HandlerConfig(
            staticConfig = staticConfig,
            replacementMap = meta.repair.replacementMap,
        )
    }

    private fun BpmnDiagnostic.bareRuleId(): String? = rule?.let(BpmnLintRuleIds::bareRuleId)
}
