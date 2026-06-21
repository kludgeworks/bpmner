/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset.internal.domain.primitives

internal object BpmnTypeMatcher {
    internal val broadTypeNames = setOf(
        BpmnTypeName.FLOW_ELEMENT,
        BpmnTypeName.FLOW_NODE,
        BpmnTypeName.TASK,
        BpmnTypeName.GATEWAY,
        BpmnTypeName.EVENT,
    )

    internal val supportedTypeNames = setOf(
        BpmnTypeName.DEFINITIONS,
        BpmnTypeName.PROCESS,
        BpmnTypeName.START_EVENT,
        BpmnTypeName.END_EVENT,
        BpmnTypeName.INTERMEDIATE_CATCH_EVENT,
        BpmnTypeName.INTERMEDIATE_THROW_EVENT,
        BpmnTypeName.BOUNDARY_EVENT,
        BpmnTypeName.USER_TASK,
        BpmnTypeName.SERVICE_TASK,
        BpmnTypeName.SCRIPT_TASK,
        BpmnTypeName.BUSINESS_RULE_TASK,
        BpmnTypeName.SEND_TASK,
        BpmnTypeName.RECEIVE_TASK,
        BpmnTypeName.MANUAL_TASK,
        BpmnTypeName.EXCLUSIVE_GATEWAY,
        BpmnTypeName.PARALLEL_GATEWAY,
        BpmnTypeName.EVENT_BASED_GATEWAY,
        BpmnTypeName.SUB_PROCESS,
        BpmnTypeName.CALL_ACTIVITY,
        BpmnTypeName.SEQUENCE_FLOW,
    )

    private val taskTypeNames = setOf(
        BpmnTypeName.USER_TASK,
        BpmnTypeName.SERVICE_TASK,
        BpmnTypeName.SCRIPT_TASK,
        BpmnTypeName.BUSINESS_RULE_TASK,
        BpmnTypeName.SEND_TASK,
        BpmnTypeName.RECEIVE_TASK,
        BpmnTypeName.MANUAL_TASK,
    )

    private val gatewayTypeNames = setOf(
        BpmnTypeName.EXCLUSIVE_GATEWAY,
        BpmnTypeName.INCLUSIVE_GATEWAY,
        BpmnTypeName.PARALLEL_GATEWAY,
        BpmnTypeName.EVENT_BASED_GATEWAY,
    )

    private val eventTypeNames = setOf(
        BpmnTypeName.START_EVENT,
        BpmnTypeName.END_EVENT,
        BpmnTypeName.INTERMEDIATE_CATCH_EVENT,
        BpmnTypeName.INTERMEDIATE_THROW_EVENT,
        BpmnTypeName.BOUNDARY_EVENT,
    )

    private val activityTypeNames = setOf(
        BpmnTypeName.SUB_PROCESS,
        BpmnTypeName.CALL_ACTIVITY,
    )

    private val flowNodeTypeNames = taskTypeNames + gatewayTypeNames + eventTypeNames + activityTypeNames

    private val flowElementTypeNames = flowNodeTypeNames + BpmnTypeName.SEQUENCE_FLOW

    private val broadTypeMembers = mapOf(
        BpmnTypeName.FLOW_ELEMENT to flowElementTypeNames,
        BpmnTypeName.FLOW_NODE to flowNodeTypeNames,
        BpmnTypeName.TASK to taskTypeNames,
        BpmnTypeName.GATEWAY to gatewayTypeNames,
        BpmnTypeName.EVENT to eventTypeNames,
    )

    /**
     * Pure type-membership check. Returns `true` iff [elementType] belongs to [targetType] —
     * either exact equality or membership in one of the broad categories (`bpmn:Task`,
     * `bpmn:Gateway`, `bpmn:Event`, `bpmn:FlowNode`, `bpmn:FlowElement`).
     *
     * No special-casing for "unsupported" BPMN types (`bpmn:InclusiveGateway`,
     * `bpmn:ComplexGateway`, `bpmn:EventBasedGateway`): if production never produces them,
     * the model context simply has no elements of that type and the primitive's targeted
     * scan returns empty. Tests that exercise those types construct synthetic elements and
     * the matcher handles them like any other type.
     */
    fun matches(elementType: String, targetType: String): Boolean {
        return targetType == elementType || elementType in broadTypeMembers[targetType].orEmpty()
    }

    /**
     * Used by [CardinalityCheck] to skip rules targeting types the production model can't
     * produce — without this guard, a `min: 1` cardinality on `bpmn:InclusiveGateway` would
     * fire on every evaluation since the count is always zero.
     */
    fun isSupportedProductionType(typeName: String): Boolean = typeName in supportedTypeNames || typeName in broadTypeNames
}

internal fun PrimitiveElement.isGateway(): Boolean = BpmnTypeMatcher.matches(typeName, BpmnTypeName.GATEWAY)

internal fun PrimitiveElement.isTask(): Boolean = BpmnTypeMatcher.matches(typeName, BpmnTypeName.TASK)

internal fun PrimitiveElement.isEvent(): Boolean = BpmnTypeMatcher.matches(typeName, BpmnTypeName.EVENT)
