/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain

import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.RepairKind
import dev.groknull.bpmner.repair.BpmnRepairAttempt
import dev.groknull.bpmner.rules.RuleRegistry
import dev.groknull.bpmner.validation.BpmnDiagnostic
import dev.groknull.bpmner.validation.BpmnLintRuleIds
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
internal class BpmnLocalFixApplier(
    private val modelFixHandlerRegistry: BpmnLocalModelFixHandlerRegistry,
    private val ruleRegistry: RuleRegistry,
    private val patchApplier: BpmnPatchApplicationPort,
    private val advancer: BpmnRepairAdvancer,
) {
    private val logger = LoggerFactory.getLogger(BpmnLocalFixApplier::class.java)

    fun applyLocalModelFix(repairEval: BpmnRepairEvaluation): BpmnRepairEvaluation {
        val attempt = repairEval.toAttempt()
        val applied = applyLocalModelFix(attempt)
            ?: throw RepairReplans.signal("no LOCAL_MODEL_FIX produced a candidate")
        logger.info("Local model fix applied on repair attempt {}", repairEval.repairAttempts + 1)
        return advancer.revalidateAndAdvance(
            prior = repairEval,
            repaired = applied.definition,
            appendedMessages = emptyList(),
            promptText = applied.reason,
        )
    }

    private fun applyLocalModelFix(attempt: BpmnRepairAttempt): LocalFixApplied? = attempt.diagnostics
        .asSequence()
        .mapNotNull { buildLocalFixCandidate(attempt.definition, it) }
        .firstNotNullOfOrNull { tryApplyLocalFix(attempt.definition, it) }

    private fun buildLocalFixCandidate(definition: BpmnDefinition, diagnostic: BpmnDiagnostic): LocalFixCandidate? {
        if (diagnostic.kind != RepairKind.LOCAL_MODEL_FIX) return null
        val handlerName = diagnostic.fixHandler ?: return null
        val elementId = diagnostic.elementId ?: return null
        val handler = modelFixHandlerRegistry.lookup(handlerName) ?: return null
        val ops = handler.buildPatch(definition, elementId, handlerConfigFor(diagnostic))
        if (ops.isEmpty()) return null
        val reason = "LOCAL_MODEL_FIX: $handlerName on $elementId"
        return LocalFixCandidate(
            handlerName = handlerName,
            elementId = elementId,
            patch = BpmnRepairPatch(operations = ops, reason = reason),
            reason = reason,
        )
    }

    private fun tryApplyLocalFix(
        definition: BpmnDefinition,
        candidate: LocalFixCandidate,
    ): LocalFixApplied? = when (val applied = patchApplier.apply(definition, candidate.patch)) {
        is PatchApplicationResult.Success -> LocalFixApplied(applied.definition, candidate.reason)

        is PatchApplicationResult.Failure -> {
            logger.warn(
                "Local model fix produced invalid patch; trying next diagnostic. handler={}, elementId={}, reason={}",
                candidate.handlerName,
                candidate.elementId,
                applied.reason,
            )
            null
        }

        PatchApplicationResult.NoOp -> null
    }

    private fun handlerConfigFor(diagnostic: BpmnDiagnostic): HandlerConfig {
        val ruleId = diagnostic.rule?.let(BpmnLintRuleIds::bareRuleId) ?: return HandlerConfig.EMPTY
        val meta = ruleRegistry.ruleByIdOrAlias(ruleId)?.metadata ?: return HandlerConfig.EMPTY
        return HandlerConfig(replacementMap = meta.repair.replacementMap)
    }

    private data class LocalFixCandidate(
        val handlerName: String,
        val elementId: String,
        val patch: BpmnRepairPatch,
        val reason: String,
    )

    private data class LocalFixApplied(val definition: BpmnDefinition, val reason: String)
}
