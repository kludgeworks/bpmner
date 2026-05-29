/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.api

/**
 * Visitor over the sealed [BpmnNode] hierarchy. All methods have null defaults so a visitor
 * can implement only the subtypes it cares about.
 *
 * Because [BpmnNode] is sealed, dispatch via [accept] is exhaustive at compile time — there
 * is no `visitUnknown` fallback. Adding a new BPMN subtype forces an addition both here and
 * in [accept].
 */
@Suppress("TooManyFunctions") // 15 visit methods — one per BpmnNode subtype, intentional
interface BpmnNodeVisitor<T> {
    fun visitStartEvent(node: BpmnStartEvent): T? = null

    fun visitUserTask(node: BpmnUserTask): T? = null

    fun visitServiceTask(node: BpmnServiceTask): T? = null

    fun visitScriptTask(node: BpmnScriptTask): T? = null

    fun visitBusinessRuleTask(node: BpmnBusinessRuleTask): T? = null

    fun visitSendTask(node: BpmnSendTask): T? = null

    fun visitReceiveTask(node: BpmnReceiveTask): T? = null

    fun visitManualTask(node: BpmnManualTask): T? = null

    fun visitExclusiveGateway(node: BpmnExclusiveGateway): T? = null

    fun visitInclusiveGateway(node: BpmnInclusiveGateway): T? = null

    fun visitParallelGateway(node: BpmnParallelGateway): T? = null

    fun visitIntermediateCatchEvent(node: BpmnIntermediateCatchEvent): T? = null

    fun visitIntermediateThrowEvent(node: BpmnIntermediateThrowEvent): T? = null

    fun visitBoundaryEvent(node: BpmnBoundaryEvent): T? = null

    fun visitEndEvent(node: BpmnEndEvent): T? = null
}

/**
 * Dispatches [this] to the matching `visitXxx` on [visitor].
 *
 * Returns `null` either when the visitor's matching method returned `null` *or* when the
 * visitor did not override that method (every `visitXxx` defaults to `null`). Callers that
 * need to distinguish "visitor didn't handle this subtype" from "visitor handled it and
 * the result is `null`" should make their visitor return a non-nullable wrapper (e.g.
 * `Optional`-like) instead of relying on `T?` semantics here.
 */
@Suppress("CyclomaticComplexMethod") // one arm per sealed subtype — the count IS the safety property
fun <T> BpmnNode.accept(visitor: BpmnNodeVisitor<T>): T? = when (this) {
    is BpmnStartEvent -> visitor.visitStartEvent(this)

    is BpmnUserTask -> visitor.visitUserTask(this)

    is BpmnServiceTask -> visitor.visitServiceTask(this)

    is BpmnScriptTask -> visitor.visitScriptTask(this)

    is BpmnBusinessRuleTask -> visitor.visitBusinessRuleTask(this)

    is BpmnSendTask -> visitor.visitSendTask(this)

    is BpmnReceiveTask -> visitor.visitReceiveTask(this)

    is BpmnManualTask -> visitor.visitManualTask(this)

    is BpmnExclusiveGateway -> visitor.visitExclusiveGateway(this)

    is BpmnInclusiveGateway -> visitor.visitInclusiveGateway(this)

    is BpmnParallelGateway -> visitor.visitParallelGateway(this)

    is BpmnIntermediateCatchEvent -> visitor.visitIntermediateCatchEvent(this)

    is BpmnIntermediateThrowEvent -> visitor.visitIntermediateThrowEvent(this)

    is BpmnBoundaryEvent -> visitor.visitBoundaryEvent(this)

    is BpmnEndEvent -> visitor.visitEndEvent(this)

    // Fallback for elements without a typed Kotlin class. Visitors are typed against the
    // canonical subtypes; unrecognized nodes have no hook and return null.
    is BpmnUnrecognizedNode -> null

    else -> null // unreachable for the canonical 15-subtype hierarchy; see KDoc on BpmnNode
}
