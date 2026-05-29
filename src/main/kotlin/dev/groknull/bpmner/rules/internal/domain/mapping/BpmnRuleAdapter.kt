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
import dev.groknull.bpmner.pkl.BpmnRule as PklBpmnRule

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
        // Pkl's `severity = "off"` is an author-level opt-out (today only UncommonAbbreviations.pkl
        // uses it). Surfacing it as RuleSeverity.WARNING would silently re-enable the rule, so
        // disabled rules drop out here — same null-return contract as no-checkPrimitive. The
        // loader counts both in the skipped tally.
        if (generated.severity == PklBpmnRule.Severity.OFF) return null

        val config = generated.checkConfig
            ?: error(
                "Rule '${generated.id}' declares checkPrimitive=$primitive but no checkConfig — " +
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
                staticConfig = staticConfigFromPkl(generated.id, generated.staticConfig),
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
        PklBpmnRule.Severity.OFF -> error("severity=off rules are filtered before this point in adapt()")
    }

    private fun repairFromPkl(pkl: PklBpmnRule.Repair): RepairMetadata = RepairMetadata(
        kind = RepairKind.valueOf(pkl.kind.name),
        safety = RepairSafety.valueOf(pkl.safety.name),
        handler = pkl.handler,
        replacementMap = pkl.replacementMap,
    )

    // Pkl's `staticConfig: Dynamic? = null` maps to Java Object. The runtime returns one of:
    //  - null (the rule didn't set staticConfig, or set it to null explicitly)
    //  - org.pkl.core.PObject (the common case — `new { foo = ... }`)
    //  - java.util.Map (Pkl Mapping)
    // DeterministicTopologyRepairStrategy reads meta.staticConfig as Map<String, Any> via
    // HandlerConfig, so we normalize at the adapter boundary rather than at every consumer.
    // Any other type is a rule-author mistake worth surfacing here, not silently swallowed.
    @Suppress("UNCHECKED_CAST")
    private fun staticConfigFromPkl(ruleId: String, raw: Any?): Map<String, Any>? = when (raw) {
        null -> null

        is org.pkl.core.PObject -> raw.properties as Map<String, Any>

        is Map<*, *> -> raw as Map<String, Any>

        else -> error(
            "Rule '$ruleId' staticConfig must be a Pkl object/mapping, got ${raw::class.java.name}",
        )
    }
}
