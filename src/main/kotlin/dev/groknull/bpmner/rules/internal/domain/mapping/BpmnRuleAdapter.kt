/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.mapping

import dev.groknull.bpmner.api.RepairKind
import dev.groknull.bpmner.api.RepairMetadata
import dev.groknull.bpmner.api.RepairSafety
import dev.groknull.bpmner.api.RuleMetadata
import dev.groknull.bpmner.api.RuleSeverity
import dev.groknull.bpmner.pkl.generated.BpmnRule as PklBpmnRule

/**
 * The output of adapting a Pkl-generated rule POJO into Kotlin domain types. The mapped
 * primitive config is carried alongside [RuleMetadata] so the loader can instantiate the
 * matching primitive check without re-walking the codegen'd structure.
 *
 * Returned only when the rule declares a `checkPrimitive`. Rules without a primitive are
 * deferred (awaiting #196) and skipped by [BpmnRuleAdapter] with a null return — the loader
 * logs these and counts them in the skipped tally.
 */
internal data class AdaptedRule(
    val metadata: RuleMetadata,
    val mappedCheck: MappedCheck,
)

internal object BpmnRuleAdapter {
    fun adapt(generated: PklBpmnRule): AdaptedRule? {
        val primitive = generated.checkPrimitive ?: return null
        val config = generated.checkConfig
            ?: error(
                "Rule '${generated.id}' declares checkPrimitive=${primitive.toString()} but no checkConfig — " +
                    "both must be set together (see CheckPrimitive.pkl)",
            )

        return AdaptedRule(
            metadata = RuleMetadata(
                id = generated.id,
                name = generated.name,
                slug = generated.slug,
                category = generated.category.name,
                intent = generated.intent,
                forModellers = generated.forModellers,
                forAI = generated.forAI,
                targetElements = generated.targetElements,
                errorMessages = generated.errorMessages,
                severity = severityFromPkl(generated.severity),
                repair = repairFromPkl(generated.repair),
                staticConfig = null,
                checkPrimitive = primitive.toString(),
                checkConfig = null,
                aliases = generated.aliases,
                deprecated = generated.isDeprecated,
                replacedBy = generated.replacedBy,
                deprecationReason = generated.deprecationReason,
            ),
            mappedCheck = CheckConfigMapper.map(config),
        )
    }

    private fun severityFromPkl(pkl: PklBpmnRule.Severity): RuleSeverity = when (pkl) {
        PklBpmnRule.Severity.ERROR -> RuleSeverity.ERROR
        PklBpmnRule.Severity.WARNING -> RuleSeverity.WARNING
        PklBpmnRule.Severity.INFO -> RuleSeverity.INFO
        // Pkl's "off" maps to WARNING with the expectation that the loader inspects severity
        // separately if it wants to drop the rule. The domain [RuleSeverity] enum has no OFF —
        // keep it that way to avoid spreading a fourth state through the diagnostic pipeline
        // for the single case where an author wants to disable a rule via Pkl. The right place
        // to suppress in that case is a future RuleProfile (Phase 2E).
        PklBpmnRule.Severity.OFF -> RuleSeverity.WARNING
    }

    private fun repairFromPkl(pkl: PklBpmnRule.Repair): RepairMetadata = RepairMetadata(
        kind = RepairKind.valueOf(pkl.kind.name),
        safety = RepairSafety.valueOf(pkl.safety.name),
        handler = pkl.handler,
        replacementMap = pkl.replacementMap,
    )
}
