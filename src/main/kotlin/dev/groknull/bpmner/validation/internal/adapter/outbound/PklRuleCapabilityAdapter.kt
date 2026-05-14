package dev.groknull.bpmner.validation.internal.adapter.outbound

import dev.groknull.bpmner.core.BpmnEditSurface
import dev.groknull.bpmner.core.BpmnLintRuleCapability
import dev.groknull.bpmner.core.BpmnRepairRoute
import dev.groknull.bpmner.core.BpmnRepairSafety
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
        val route = BpmnRepairRoute.valueOf(repair.route)
        return BpmnLintRuleCapability(
            id = rule.id,
            repairRoute = route,
            editSurface = BpmnEditSurface.valueOf(repair.editSurface),
            repairSafety = BpmnRepairSafety.valueOf(repair.safety),
            fixHandler = repair.handler,
            handlerExists = route == BpmnRepairRoute.LOCAL_XML || route == BpmnRepairRoute.LOCAL_MODEL,
            replacementMap = repair.replacementMap,
        )
    }
}
