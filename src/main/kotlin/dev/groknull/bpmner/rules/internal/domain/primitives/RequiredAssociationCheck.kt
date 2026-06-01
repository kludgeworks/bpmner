/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.primitives

import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.RuleDiagnostic
import dev.groknull.bpmner.api.RuleMetadata

internal class RequiredAssociationCheck {
    fun evaluate(
        ctx: BpmnDefinitionContext,
        metadata: RuleMetadata,
        config: RequiredAssociationCheckConfig,
    ): List<RuleDiagnostic> = evaluate(ctx.toPrimitiveModelContext(), metadata, config)

    fun evaluate(
        model: PrimitiveModelContext,
        metadata: RuleMetadata,
        config: RequiredAssociationCheckConfig,
    ): List<RuleDiagnostic> {
        // Dormant in production until the BPMN model carries `bpmn:Association` edges (#196).
        // Without the capability, "no associations found" applies to every targeted element —
        // a false positive on every run. Return empty until the capability flips on.
        if (!model.supports(ModelCapability.ASSOCIATIONS)) return emptyList()
        return metadata.targetedElements(model)
            .filter { config.appliesWhenProperty == null || it.property(config.appliesWhenProperty) != null }
            .filter { element ->
                val elementId = element.id ?: return@filter false
                // OUTBOUND matches associations whose sourceRef is this element; INBOUND matches
                // those whose targetRef is — then we check the OTHER end's type against targetTypes.
                val associations = model.associations.filter { association ->
                    association.typeName == config.association && association.endFor(config.direction) == elementId
                }
                associations.none { association ->
                    val otherEnd = model.elementsById[association.otherEndFor(config.direction)] ?: return@none false
                    config.targetTypes.isEmpty() ||
                        config.targetTypes.any { BpmnTypeMatcher.matches(otherEnd.typeName, it) }
                }
            }
            .map { metadata.diagnostic(it.id, config.association) }
    }

    private fun PrimitiveAssociation.endFor(direction: AssociationDirection): String = when (direction) {
        AssociationDirection.OUTBOUND -> sourceRef
        AssociationDirection.INBOUND -> targetRef
    }

    private fun PrimitiveAssociation.otherEndFor(direction: AssociationDirection): String = when (direction) {
        AssociationDirection.OUTBOUND -> targetRef
        AssociationDirection.INBOUND -> sourceRef
    }
}
