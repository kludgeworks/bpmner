/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.primitives

import dev.groknull.bpmner.bpmn.BpmnDefinitionContext
import dev.groknull.bpmner.bpmn.RuleDiagnostic
import dev.groknull.bpmner.bpmn.RuleMetadata

internal class PoolLabelCheck {
    fun evaluate(
        ctx: BpmnDefinitionContext,
        metadata: RuleMetadata,
        config: PoolLabelCheckConfig,
    ): List<RuleDiagnostic> = evaluate(ctx.toPrimitiveModelContext(), metadata, config)

    fun evaluate(
        model: PrimitiveModelContext,
        metadata: RuleMetadata,
        config: PoolLabelCheckConfig,
    ): List<RuleDiagnostic> = metadata.targetedElements(model)
        .filter { it.typeName == "bpmn:Participant" || it.typeName == "bpmn:Lane" }
        .filter { violates(it, config.mode) }
        .map { metadata.diagnostic(it.id) }

    private fun violates(element: PrimitiveElement, mode: PoolLabelMode): Boolean = when (mode) {
        PoolLabelMode.WHITE_BOX_NAMED_BY_PROCESS ->
            element.property("poolKind") == "WHITE_BOX" &&
                element.property("name") != element.property("processName")

        PoolLabelMode.BLACK_BOX_NAMED_BY_EXTERNAL_ENTITY_OR_PROCESS ->
            element.property("poolKind") == "BLACK_BOX" &&
                element.property("name").isNullOrBlank()

        PoolLabelMode.CHILD_DIAGRAMS_KEEP_POOL_PROCESS_NAME ->
            element.property("isChildDiagram") == "true" &&
                element.property("name") != element.property("processName")

        PoolLabelMode.LANE_LABELS_BUSINESS_ROLES_PERFORMERS ->
            element.typeName == "bpmn:Lane" &&
                element.property("role").isNullOrBlank()
    }
}
