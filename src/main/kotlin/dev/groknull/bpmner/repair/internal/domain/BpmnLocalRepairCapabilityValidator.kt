/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
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
        )
    }

    internal fun validate(
        capabilities: Map<String, BpmnLintRuleCapability>,
        kotlinHandlerNames: Set<String>,
    ) {
        val unbound =
            capabilities.values
                .distinct()
                .mapNotNull { cap -> describeUnbound(cap, kotlinHandlerNames) }
        if (unbound.isNotEmpty()) {
            throw BpmnRepairCapabilityValidationException(
                "Unbound rule handler(s): ${unbound.joinToString()}",
            )
        }
        logger.info(
            "Validated repair handler bindings: {} capabilities, {} Kotlin handler(s)",
            capabilities.size,
            kotlinHandlerNames.size,
        )
    }

    private fun describeUnbound(
        cap: BpmnLintRuleCapability,
        kotlinHandlerNames: Set<String>,
    ): String? =
        when (cap.kind) {
            RepairKind.LOCAL_MODEL_FIX -> describeUnboundLocal(cap, "LOCAL_MODEL_FIX", "Kotlin", kotlinHandlerNames)
            RepairKind.LOCAL_XML_FIX -> null // TS bundle validates its own registry at startup
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
}
