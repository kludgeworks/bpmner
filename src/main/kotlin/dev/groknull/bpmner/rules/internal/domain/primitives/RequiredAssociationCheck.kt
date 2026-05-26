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
            .filter { source ->
                val sourceId = source.id ?: return@filter false
                val associations = model.associations.filter {
                    it.typeName == config.association && it.sourceRef == sourceId
                }
                associations.none { association ->
                    val target = model.elementsById[association.targetRef] ?: return@none false
                    config.targetTypes.isEmpty() ||
                        config.targetTypes.any { BpmnTypeMatcher.matches(target.typeName, it) }
                }
            }
            .map { metadata.diagnostic(it.id, config.association) }
    }
}
