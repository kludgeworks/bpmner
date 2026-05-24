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
@Suppress("TooManyFunctions") // 14 visit methods — one per BpmnNode subtype, intentional
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

    fun visitParallelGateway(node: BpmnParallelGateway): T? = null

    fun visitIntermediateCatchEvent(node: BpmnIntermediateCatchEvent): T? = null

    fun visitIntermediateThrowEvent(node: BpmnIntermediateThrowEvent): T? = null

    fun visitBoundaryEvent(node: BpmnBoundaryEvent): T? = null

    fun visitEndEvent(node: BpmnEndEvent): T? = null
}

@Suppress("CyclomaticComplexMethod") // one arm per sealed subtype — the count IS the safety property
fun <T> BpmnNode.accept(visitor: BpmnNodeVisitor<T>): T? =
    when (this) {
        is BpmnStartEvent -> visitor.visitStartEvent(this)
        is BpmnUserTask -> visitor.visitUserTask(this)
        is BpmnServiceTask -> visitor.visitServiceTask(this)
        is BpmnScriptTask -> visitor.visitScriptTask(this)
        is BpmnBusinessRuleTask -> visitor.visitBusinessRuleTask(this)
        is BpmnSendTask -> visitor.visitSendTask(this)
        is BpmnReceiveTask -> visitor.visitReceiveTask(this)
        is BpmnManualTask -> visitor.visitManualTask(this)
        is BpmnExclusiveGateway -> visitor.visitExclusiveGateway(this)
        is BpmnParallelGateway -> visitor.visitParallelGateway(this)
        is BpmnIntermediateCatchEvent -> visitor.visitIntermediateCatchEvent(this)
        is BpmnIntermediateThrowEvent -> visitor.visitIntermediateThrowEvent(this)
        is BpmnBoundaryEvent -> visitor.visitBoundaryEvent(this)
        is BpmnEndEvent -> visitor.visitEndEvent(this)
    }
