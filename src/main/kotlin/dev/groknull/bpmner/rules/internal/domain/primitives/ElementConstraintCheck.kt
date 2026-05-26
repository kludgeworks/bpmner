/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.primitives

import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.RuleDiagnostic
import dev.groknull.bpmner.api.RuleMetadata
import java.util.regex.PatternSyntaxException

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
            .filter { element -> allowed.none { BpmnTypeMatcher.matches(element.typeName, it) } }
            .map { metadata.diagnostic(it.id, it.typeName) }
    }

    private fun timerExpression(
        model: PrimitiveModelContext,
        metadata: RuleMetadata,
        config: ElementConstraintCheckConfig,
    ): List<RuleDiagnostic> {
        val patternSource = config.constraints["pattern"] as? String
        val pattern = if (patternSource == null) {
            null
        } else {
            try {
                Regex(patternSource)
            } catch (e: PatternSyntaxException) {
                return listOf(
                    metadata.configError(
                        "timerExpression pattern '$patternSource' is not a valid regex: ${e.description}",
                    ),
                )
            }
        }
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
                // BPMN 2.0 §10.5.4.6 — event-based gateways may target intermediate catch
                // events OR a `bpmn:ReceiveTask` (which behaves as a catching message event
                // for routing purposes). Anything else is invalid.
                !target.isEvent() && target.typeName != "bpmn:ReceiveTask"
            }
        }
        .map { metadata.diagnostic(it.id) }
}
