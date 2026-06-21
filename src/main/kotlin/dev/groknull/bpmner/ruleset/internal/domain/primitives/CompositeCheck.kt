/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset.internal.domain.primitives

import dev.groknull.bpmner.bpmn.BpmnDefinitionContext
import dev.groknull.bpmner.bpmn.RuleDiagnostic
import dev.groknull.bpmner.bpmn.RuleMetadata
import dev.groknull.bpmner.ruleset.internal.domain.nlp.BpmnNlp

/**
 * Composes several deterministic sub-checks under one Pkl rule, attributing each
 * sub-check's diagnostics to a declared key in the parent rule's `errorMessages`.
 *
 * For a rule like `BoundaryEventConstraints` with four error codes
 * (`detached`, `incoming`, `outgoing`, `errorInterrupting`), each `SubCheck` declares which
 * code it owns and supplies the typed primitive config that detects that condition. The
 * engine emits four distinct diagnostics — one per violated sub-check — all attributed to
 * the composite rule's `id` but each with the correct `diagnosticCode` and message.
 *
 * Composition rules:
 *  - `subChecks` must be deterministic — `LlmCheckRuleConfig` is rejected (lives in
 *    [dev.groknull.bpmner.ruleset.internal.adapter.inbound.LlmRuleAgent]).
 *  - Nesting (`CompositeCheckConfig` inside another) is rejected to keep code attribution
 *    unambiguous.
 *  - Each sub-check's `diagnosticCode` must exist in the parent's `errorMessages` and must
 *    not be `"default"`. Violations of either constraint produce a single
 *    `rule-config-error` diagnostic from this primitive — actionable at the rule author,
 *    not silently swallowed.
 *
 * The optional `targetTypes` field narrows the element scope for the duration of
 * evaluation, overriding the outer rule's `targetElements` when non-empty. Useful when a
 * rule's `targetElements` is broad (`bpmn:FlowNode`) but its sub-checks only apply to a
 * narrower set (`bpmn:BoundaryEvent`).
 */
internal object CompositeCheck {
    fun evaluate(
        ctx: BpmnDefinitionContext,
        metadata: RuleMetadata,
        config: CompositeCheckConfig,
        nlp: BpmnNlp,
    ): List<RuleDiagnostic> = evaluate(ctx.toPrimitiveModelContext(), metadata, config, nlp)

    fun evaluate(
        model: PrimitiveModelContext,
        metadata: RuleMetadata,
        config: CompositeCheckConfig,
        nlp: BpmnNlp,
    ): List<RuleDiagnostic> {
        if (config.subChecks.isEmpty()) return emptyList()
        val effectiveMetadata = if (config.targetTypes.isEmpty()) {
            metadata
        } else {
            metadata.copy(targetElements = config.targetTypes)
        }
        return config.subChecks.flatMap { sub -> evaluateSubCheck(model, effectiveMetadata, sub, nlp) }
    }

    private fun evaluateSubCheck(
        model: PrimitiveModelContext,
        parentMetadata: RuleMetadata,
        sub: SubCheckConfig,
        nlp: BpmnNlp,
    ): List<RuleDiagnostic> {
        if (sub.diagnosticCode == "default") {
            return listOf(parentMetadata.configError("SubCheck.diagnosticCode must not be \"default\""))
        }
        val template = parentMetadata.errorMessages[sub.diagnosticCode]
            ?: return listOf(
                parentMetadata.configError(
                    "SubCheck.diagnosticCode \"${sub.diagnosticCode}\" not found in parent errorMessages " +
                        "(known keys: ${parentMetadata.errorMessages.keys})",
                ),
            )
        // Narrow the metadata so the sub-check primitive's call to `metadata.diagnostic(...)`
        // picks `sub.diagnosticCode` (the only non-"default" key) and the matching message.
        val subMetadata = parentMetadata.copy(errorMessages = mapOf(sub.diagnosticCode to template))
        return SubCheckEvaluator.evaluate(model, subMetadata, sub.config, nlp)
    }
}
