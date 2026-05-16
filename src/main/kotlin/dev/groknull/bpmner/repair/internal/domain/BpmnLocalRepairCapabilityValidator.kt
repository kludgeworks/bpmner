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

package dev.groknull.bpmner.repair.internal.domain

import dev.groknull.bpmner.validation.BpmnLintRuleCapability
import dev.groknull.bpmner.validation.BpmnLintingPort
import dev.groknull.bpmner.validation.RepairKind
import org.slf4j.LoggerFactory
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

internal class BpmnRepairCapabilityValidationException(
    message: String,
) : IllegalStateException(message)

@Component
internal class BpmnLocalRepairCapabilityValidator(
    private val lintingPort: BpmnLintingPort,
    private val modelFixHandlerRegistry: BpmnLocalModelFixHandlerRegistry,
) {
    private val logger = LoggerFactory.getLogger(BpmnLocalRepairCapabilityValidator::class.java)

    @EventListener(ContextRefreshedEvent::class)
    fun validateOnStartup() {
        validate(
            capabilities = lintingPort.lintRuleCapabilities(),
            kotlinHandlerNames = modelFixHandlerRegistry.registeredNames(),
            tsHandlerNames = KNOWN_TS_HANDLERS,
        )
    }

    internal fun validate(
        capabilities: Map<String, BpmnLintRuleCapability>,
        kotlinHandlerNames: Set<String>,
        tsHandlerNames: Set<String>,
    ) {
        val unbound =
            capabilities.values
                .distinct()
                .mapNotNull { cap -> describeUnbound(cap, kotlinHandlerNames, tsHandlerNames) }
        if (unbound.isNotEmpty()) {
            throw BpmnRepairCapabilityValidationException(
                "Unbound rule handler(s): ${unbound.joinToString()}",
            )
        }
        logger.info(
            "Validated repair handler bindings: {} capabilities, {} Kotlin handler(s), {} TS handler(s)",
            capabilities.size,
            kotlinHandlerNames.size,
            tsHandlerNames.size,
        )
    }

    private fun describeUnbound(
        cap: BpmnLintRuleCapability,
        kotlinHandlerNames: Set<String>,
        tsHandlerNames: Set<String>,
    ): String? =
        when (cap.kind) {
            RepairKind.LOCAL_MODEL_FIX -> describeUnboundLocal(cap, "LOCAL_MODEL_FIX", "Kotlin", kotlinHandlerNames)
            RepairKind.LOCAL_XML_FIX -> describeUnboundLocal(cap, "LOCAL_XML_FIX", "TS", tsHandlerNames)
            else -> null
        }

    private fun describeUnboundLocal(
        cap: BpmnLintRuleCapability,
        kindLabel: String,
        sideLabel: String,
        knownHandlerNames: Set<String>,
    ): String? {
        val handler = cap.fixHandler
        return when {
            handler == null -> "${cap.id} ($kindLabel, no handler declared)"
            handler !in knownHandlerNames -> "${cap.id}=$handler ($kindLabel, $sideLabel)"
            else -> null
        }
    }

    companion object {
        // Mirrors keys of HANDLERS map in linter/src/auto-fix/registry.ts.
        // Must stay in sync with the TS bundle.
        internal val KNOWN_TS_HANDLERS: Set<String> =
            setOf(
                "clearName",
                "removeTerminateDefinition",
                "fixSentenceCase",
                "expandAbbreviations",
                "stripTypeWords",
                "deleteSequenceFlow",
                "deleteBlankStartEvents",
                "keepFirstEventDefinition",
                "deleteIncomingFlows",
            )
    }
}
