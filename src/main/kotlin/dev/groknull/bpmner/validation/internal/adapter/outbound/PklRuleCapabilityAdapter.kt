/*
 * Copyright (c) 2026 The Project Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package dev.groknull.bpmner.validation.internal.adapter.outbound

import dev.groknull.bpmner.validation.BpmnLintRuleCapability
import dev.groknull.bpmner.validation.BpmnRepairSafety
import dev.groknull.bpmner.validation.RepairKind
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
            logger.debug("Loaded {} lint rule capabilities from Pkl-generated catalog", it.size)
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
            repairSafety = BpmnRepairSafety.valueOf(repair.safety),
            fixHandler = repair.handler,
            handlerExists = kind.isLocal(),
            replacementMap = repair.replacementMap,
            layoutSensitive = rule.layoutSensitive,
        )
    }
}
