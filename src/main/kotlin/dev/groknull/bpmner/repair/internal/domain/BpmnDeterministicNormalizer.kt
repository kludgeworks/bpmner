/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain

import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.RepairKind
import dev.groknull.bpmner.conformance.BpmnDiagnostic
import dev.groknull.bpmner.conformance.BpmnFingerprintService
import dev.groknull.bpmner.conformance.BpmnLintRuleIds
import dev.groknull.bpmner.ruleset.RuleRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
internal class BpmnDeterministicNormalizer(
    private val modelFixHandlerRegistry: BpmnLocalModelFixHandlerRegistry,
    private val ruleRegistry: RuleRegistry,
    private val patchApplier: BpmnPatchApplicationPort,
    private val fingerprints: BpmnFingerprintService,
    private val advancer: BpmnRepairAdvancer,
) {
    private val logger = LoggerFactory.getLogger(BpmnDeterministicNormalizer::class.java)

    fun normalize(repairEval: BpmnRepairEvaluation): BpmnRepairEvaluation {
        var definition = repairEval.definition
        val seenFingerprints = mutableSetOf(fingerprints.definitionFingerprint(definition))
        val reasons = mutableListOf<String>()

        while (true) {
            var changed = false
            repairEval.evaluation.blockingDiagnostics.forEach { diagnostic ->
                val candidate = buildLocalFixCandidate(definition, diagnostic) ?: return@forEach
                val applied = tryApplyLocalFix(definition, candidate) ?: return@forEach
                if (applied == definition) return@forEach

                definition = applied
                changed = true
                reasons += candidate.reason
                val fingerprint = fingerprints.definitionFingerprint(definition)
                check(seenFingerprints.add(fingerprint)) {
                    "deterministic normalization cycle detected after ${candidate.reason}"
                }
            }
            if (!changed) break
        }

        if (definition == repairEval.definition) return repairEval
        logger.info("Deterministic normalization applied {} local repair(s)", reasons.size)
        return advancer.revalidateAndAdvance(
            prior = repairEval,
            repaired = definition,
            appendedMessages = emptyList(),
            promptText = reasons.joinToString("; "),
            modelRepair = false,
        )
    }

    private fun buildLocalFixCandidate(definition: BpmnDefinition, diagnostic: BpmnDiagnostic): LocalFixCandidate? {
        if (diagnostic.kind != RepairKind.LOCAL_MODEL_FIX) return null
        val handlerName = diagnostic.fixHandler ?: return null
        val elementId = diagnostic.elementId ?: return null
        val handler = modelFixHandlerRegistry.lookup(handlerName) ?: return null
        val ops = handler.buildPatch(definition, elementId, handlerConfigFor(diagnostic))
        if (ops.isEmpty()) return null
        val reason = "LOCAL_MODEL_FIX: $handlerName on $elementId"
        return LocalFixCandidate(handlerName, elementId, BpmnRepairPatch(ops, reason), reason)
    }

    private fun tryApplyLocalFix(definition: BpmnDefinition, candidate: LocalFixCandidate): BpmnDefinition? =
        when (val applied = patchApplier.apply(definition, candidate.patch)) {
            is PatchApplicationResult.Success -> applied.definition
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
}
