/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.primitives

import dev.groknull.bpmner.bpmn.BpmnDefinitionContext
import dev.groknull.bpmner.bpmn.RuleDiagnostic
import dev.groknull.bpmner.bpmn.RuleMetadata

internal class PairingCheck {
    fun evaluate(
        ctx: BpmnDefinitionContext,
        metadata: RuleMetadata,
        config: PairingCheckConfig,
    ): List<RuleDiagnostic> = evaluate(ctx.toPrimitiveModelContext(), metadata, config)

    fun evaluate(
        model: PrimitiveModelContext,
        metadata: RuleMetadata,
        config: PairingCheckConfig,
    ): List<RuleDiagnostic> = when (config.mode) {
        PairingMode.ERROR_END_BOUNDARY -> errorEndBoundary(model, metadata)
        PairingMode.LINK_PAIRING -> linkPairing(model, metadata)
        PairingMode.MESSAGE_START_FLOW -> messageStartFlow(model, metadata)
    }

    private fun errorEndBoundary(
        model: PrimitiveModelContext,
        metadata: RuleMetadata,
    ): List<RuleDiagnostic> {
        val boundaryErrorRefs = model.elements
            .filter { it.typeName == "bpmn:BoundaryEvent" && it.property("eventDefinition") == "ERROR" }
            .mapNotNull { it.property("errorRef") }
            .toSet()
        return metadata.targetedElements(model)
            .filter { it.typeName == "bpmn:EndEvent" && it.property("eventDefinition") == "ERROR" }
            .filter { it.property("errorRef") !in boundaryErrorRefs }
            .map { metadata.diagnostic(it.id, it.property("errorRef")) }
    }

    private fun linkPairing(
        model: PrimitiveModelContext,
        metadata: RuleMetadata,
    ): List<RuleDiagnostic> {
        val links = model.elements.filter { it.property("eventDefinition") == "LINK" }
        val grouped = links.groupBy { it.property("linkRef") }
        return grouped.values
            .filter { pair ->
                pair.count { it.typeName == "bpmn:IntermediateThrowEvent" } != 1 ||
                    pair.count { it.typeName == "bpmn:IntermediateCatchEvent" } != 1
            }
            .flatMap { pair -> pair.map { metadata.diagnostic(it.id, it.property("linkRef")) } }
    }

    private fun messageStartFlow(
        model: PrimitiveModelContext,
        metadata: RuleMetadata,
    ): List<RuleDiagnostic> {
        // Dormant in production until the BPMN model carries `bpmn:MessageFlow` edges (#196).
        // Without the capability, every message start event would be flagged (since
        // `messageFlows` is always empty in production) — a false positive on every run.
        if (!model.supports(ModelCapability.MESSAGE_FLOWS)) return emptyList()
        return metadata.targetedElements(model)
            .filter { it.typeName == "bpmn:StartEvent" && it.property("eventDefinition") == "MESSAGE" }
            .filter { start ->
                val id = start.id ?: return@filter false
                model.messageFlows.none { it.targetRef == id && it.name?.isNotBlank() == true }
            }
            .map { metadata.diagnostic(it.id, it.property("messageRef")) }
    }
}
