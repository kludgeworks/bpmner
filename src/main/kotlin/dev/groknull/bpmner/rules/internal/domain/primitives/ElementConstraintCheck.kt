/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.primitives

import dev.groknull.bpmner.bpmn.BpmnDefinitionContext
import dev.groknull.bpmner.bpmn.RuleDiagnostic
import dev.groknull.bpmner.bpmn.RuleMetadata
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
        ElementConstraintMode.BOUNDARY_ATTACHED -> boundaryAttached(model, metadata)
        ElementConstraintMode.BOUNDARY_SINGLE_OUTGOING -> boundarySingleOutgoing(model, metadata)
        ElementConstraintMode.BOUNDARY_ERROR_INTERRUPTING -> boundaryErrorInterrupting(model, metadata)
    }

    private fun allowedSubset(
        model: PrimitiveModelContext,
        metadata: RuleMetadata,
        config: ElementConstraintCheckConfig,
    ): List<RuleDiagnostic> {
        // The `allowed` constraint accepts either a `List<String>` or a comma-separated
        // `String`. The `String` path is the workaround for a `pkl-config-java` codegen gap:
        // a Pkl `List` value inside a `Mapping<String, Any>` slot codegens as a
        // `java.lang.Object` field and no built-in converter handles `pkl.base#List → Object`.
        // Either path is acceptable; an empty result means "no exceptions — every
        // `targetedElement` fires".
        val allowed = when (val raw = config.constraints["allowed"]) {
            is List<*> -> raw.filterIsInstance<String>()
            is String -> raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            else -> emptyList()
        }
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

    // detached — a boundary event's `attachedToRef` must resolve to an activity in the model.
    // Activities are the seven task kinds; an unresolved or non-task target means the event is detached.
    // TODO(#191): extend `isTask()` to cover subprocess attachment.
    private fun boundaryAttached(
        model: PrimitiveModelContext,
        metadata: RuleMetadata,
    ): List<RuleDiagnostic> = metadata.targetedElements(model)
        .filter { boundary ->
            val target = boundary.property("attachedToRef")?.let { model.elementsById[it] }
            target == null || !target.isTask()
        }
        .map { metadata.diagnostic(it.id) }

    // outgoing — a boundary event routes its exception path through exactly one outgoing flow.
    private fun boundarySingleOutgoing(
        model: PrimitiveModelContext,
        metadata: RuleMetadata,
    ): List<RuleDiagnostic> = metadata.targetedElements(model)
        .filter { (model.outgoingCounts[it.id] ?: 0) != 1 }
        .map { metadata.diagnostic(it.id) }

    // errorInterrupting — error boundary events cannot be non-interrupting (BPMN 2.0 §10.5.4).
    private fun boundaryErrorInterrupting(
        model: PrimitiveModelContext,
        metadata: RuleMetadata,
    ): List<RuleDiagnostic> = metadata.targetedElements(model)
        .filter { it.property("eventDefinition") == "ERROR" && it.property("cancelActivity") == "false" }
        .map { metadata.diagnostic(it.id) }
}
