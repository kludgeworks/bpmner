/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.primitives

import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.RuleDiagnostic
import dev.groknull.bpmner.api.RuleMetadata

internal class ElementConstraintCheck {
    fun evaluate(
        ctx: BpmnDefinitionContext,
        metadata: RuleMetadata,
        config: ElementConstraintCheckConfig,
    ): List<RuleDiagnostic> = evaluate(ctx.toPrimitiveModelContext(), metadata, config)

    fun evaluate(
        model: PrimitiveModelContext,
        metadata: RuleMetadata,
        config: ElementConstraintCheckConfig,
    ): List<RuleDiagnostic> = when (config.mode) {
        ElementConstraintMode.ALLOWED_ELEMENT_SUBSET -> allowedSubset(model, metadata, config)
        ElementConstraintMode.TIMER_EXPRESSION -> timerExpression(model, metadata, config)
        ElementConstraintMode.PARALLEL_GATEWAY_STRUCTURE -> parallelGatewayStructure(model, metadata)
        ElementConstraintMode.EVENT_BASED_GATEWAY_DIRECT_EVENTS -> eventBasedGatewayDirectEvents(model, metadata)
    }

    private fun allowedSubset(
        model: PrimitiveModelContext,
        metadata: RuleMetadata,
        config: ElementConstraintCheckConfig,
    ): List<RuleDiagnostic> {
        val allowed = (config.constraints["allowed"] as? List<*>)?.filterIsInstance<String>().orEmpty()
        return metadata.targetedElements(model)
            .filter { element -> allowed.none { BpmnTypeMatcher.matches(element.typeName, it, model.synthetic) } }
            .map { metadata.diagnostic(it.id, it.typeName) }
    }

    private fun timerExpression(
        model: PrimitiveModelContext,
        metadata: RuleMetadata,
        config: ElementConstraintCheckConfig,
    ): List<RuleDiagnostic> {
        val pattern = (config.constraints["pattern"] as? String)?.let(::Regex)
        return metadata.targetedElements(model)
            .filter { it.property("eventDefinition") == "TIMER" }
            .filter {
                val expression = it.property("timerExpression")
                expression.isNullOrBlank() || pattern?.matches(expression) == false
            }
            .map { metadata.diagnostic(it.id, it.property("timerExpression")) }
    }

    private fun parallelGatewayStructure(
        model: PrimitiveModelContext,
        metadata: RuleMetadata,
    ): List<RuleDiagnostic> = metadata.targetedElements(model)
        .filter { it.typeName == "bpmn:ParallelGateway" }
        .filter {
            val id = it.id ?: return@filter false
            (model.incomingCounts[id] ?: 0) >= 2 && (model.outgoingCounts[id] ?: 0) >= 2
        }
        .map { metadata.diagnostic(it.id) }

    private fun eventBasedGatewayDirectEvents(
        model: PrimitiveModelContext,
        metadata: RuleMetadata,
    ): List<RuleDiagnostic> = metadata.targetedElements(model)
        .filter { it.typeName == "bpmn:EventBasedGateway" }
        .filter { gateway ->
            val id = gateway.id ?: return@filter false
            model.edgesFrom[id].orEmpty().any { flow ->
                val target = model.elementsById[flow.targetRef] ?: return@any false
                !target.isEvent()
            }
        }
        .map { metadata.diagnostic(it.id) }
}
