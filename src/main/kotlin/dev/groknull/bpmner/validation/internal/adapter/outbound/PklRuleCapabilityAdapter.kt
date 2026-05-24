/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.validation.internal.adapter.outbound

import dev.groknull.bpmner.api.RepairKind
import dev.groknull.bpmner.api.RepairSafety
import dev.groknull.bpmner.validation.BpmnLintRuleCapability
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
internal class PklRuleCapabilityAdapter(
    private val catalogService: RuleCatalogService,
) {
    private val logger = LoggerFactory.getLogger(PklRuleCapabilityAdapter::class.java)

    fun loadCapabilities(): Map<String, BpmnLintRuleCapability> {
        logger.debug("Loading lint rule capabilities from Pkl-generated catalog")
        val rules = catalogService.catalog.rules
        return buildCapabilityMap(rules).also {
            logger.info("Loaded {} lint rule capabilities from Pkl-generated catalog", it.size)
        }
    }

    private fun buildCapabilityMap(rules: List<BpmnRuleMetadata>): Map<String, BpmnLintRuleCapability> =
        buildMap {
            for (rule in rules) {
                val capability = toCapability(rule)
                put(rule.id, capability)
                rule.aliases.forEach { alias -> put(alias, capability) }
            }
        }

    internal fun toCapability(rule: BpmnRuleMetadata): BpmnLintRuleCapability {
        val repair = rule.repair
        val kind = RepairKind.valueOf(repair.kind)
        return BpmnLintRuleCapability(
            id = rule.id,
            kind = kind,
            repairSafety = RepairSafety.valueOf(repair.safety),
            fixHandler = repair.handler,
            handlerExists = kind.isLocal(),
            replacementMap = repair.replacementMap,
        )
    }
}
