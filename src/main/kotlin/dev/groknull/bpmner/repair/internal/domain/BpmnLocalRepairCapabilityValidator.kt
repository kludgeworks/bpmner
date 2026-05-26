/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain

import dev.groknull.bpmner.api.RepairKind
import dev.groknull.bpmner.validation.BpmnLintRuleCapability
import dev.groknull.bpmner.validation.BpmnLintingPort
import org.slf4j.LoggerFactory
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

internal class BpmnRepairCapabilityValidationException(
    message: String,
) : IllegalStateException(message)

/**
 * Startup check that every `LOCAL_MODEL_FIX` rule binds to a registered Kotlin handler.
 *
 * Until #243 collapsed `LOCAL_XML_FIX` into `LOCAL_MODEL_FIX`, this validator also enforced a
 * parallel TS-handler registry mirrored from `linter/src/auto-fix/registry.ts`. After the
 * collapse, no rule declares `LOCAL_XML_FIX` (the kind itself is `@Deprecated` and slated for
 * deletion in Phase 3), so the TS check is gone. Any stale `LOCAL_XML_FIX` capability that
 * sneaks in from an external catalog is silently ignored — startup never tried to dispatch it
 * anyway.
 */
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
    ): String? = when (cap.kind) {
        RepairKind.LOCAL_MODEL_FIX -> describeUnboundLocal(cap, kotlinHandlerNames)
        else -> null
    }

    private fun describeUnboundLocal(
        cap: BpmnLintRuleCapability,
        knownHandlerNames: Set<String>,
    ): String? {
        val handler = cap.fixHandler
        return when {
            handler == null -> "${cap.id} (LOCAL_MODEL_FIX, no handler declared)"
            handler !in knownHandlerNames -> "${cap.id}=$handler (LOCAL_MODEL_FIX, Kotlin)"
            else -> null
        }
    }
}
