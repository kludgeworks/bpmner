/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.bpmn

/**
 * Single-tier bpmn kernel — concrete types and grouping markers live in this package.
 * The concrete data classes are in [BpmnDomain.kt]; the grouping markers and cross-cutting
 * utilities are here.
 *
 * [BpmnNode] is the sealed root; all subtypes are in this package, giving bpmn-side
 * `when` blocks compile-time exhaustiveness. The grouping markers below ([BpmnTask],
 * [BpmnGateway], [BpmnEvent]) are themselves sealed, constraining their subtypes to this
 * package as well.
 */

/** Grouping marker for activity-position nodes; supports [BpmnNode.isTask] dispatch. */
sealed interface BpmnTask : BpmnNode {
    /**
     * Multi-instance loop characteristics, or `null` for an ordinary single-run activity.
     * Present when the activity runs once per item in a collection. Cross-cutting across all
     * task kinds; events and gateways never carry it. Declared here so callers and the rule
     * engine read it polymorphically over any task without an exhaustive `when`.
     */
    val multiInstance: MultiInstanceLoopCharacteristics?

    /**
     * Standard-loop characteristics, or `null` for an ordinary single-run activity. Present when
     * the activity repeats until a condition is met (a while/until/retry loop). Cross-cutting
     * across all task kinds, declared here for polymorphic reads; distinct from [multiInstance]
     * (which runs once per item in a collection rather than repeating until a condition holds).
     */
    val standardLoop: StandardLoopCharacteristics?
}

/** Grouping marker for gateway nodes. */
sealed interface BpmnGateway : BpmnNode

/** Grouping marker for event-position nodes carrying an event definition. */
sealed interface BpmnEvent : BpmnNode {
    val eventDefinition: BpmnEventDefinition
}

/**
 * The discriminator string for [this] node, matching the `@JsonSubTypes` names on the
 * concrete data classes in [BpmnDomain.kt]. Kept as a property extension to preserve the
 * existing `node.typeName` call-site syntax across the codebase.
 *
 * NOTE: the string literals here must stay in sync with the `name` values in the
 * `@JsonSubTypes` annotation on [BpmnNode]. The exhaustive `when` catches missing
 * arms when a new subtype is added but cannot catch a typo or divergence; if a subtype is
 * renamed, update both lists together.
 */
val BpmnNode.typeName: String
    get() =
        when (this) {
            is BpmnStartEvent -> "START_EVENT"
            is BpmnUserTask -> "USER_TASK"
            is BpmnServiceTask -> "SERVICE_TASK"
            is BpmnScriptTask -> "SCRIPT_TASK"
            is BpmnBusinessRuleTask -> "BUSINESS_RULE_TASK"
            is BpmnSendTask -> "SEND_TASK"
            is BpmnReceiveTask -> "RECEIVE_TASK"
            is BpmnManualTask -> "MANUAL_TASK"
            is BpmnExclusiveGateway -> "EXCLUSIVE_GATEWAY"
            is BpmnInclusiveGateway -> "INCLUSIVE_GATEWAY"
            is BpmnParallelGateway -> "PARALLEL_GATEWAY"
            is BpmnEventBasedGateway -> "EVENT_BASED_GATEWAY"
            is BpmnIntermediateCatchEvent -> "INTERMEDIATE_CATCH_EVENT"
            is BpmnIntermediateThrowEvent -> "INTERMEDIATE_THROW_EVENT"
            is BpmnBoundaryEvent -> "BOUNDARY_EVENT"
            is BpmnEndEvent -> "END_EVENT"
            is BpmnSubProcess -> "SUB_PROCESS"
            is BpmnCallActivity -> "CALL_ACTIVITY"
            is BpmnUnrecognizedNode -> "UNRECOGNIZED:$bpmnType"
        }

/**
 * True when [this] is one of the BPMN task subtypes. Backed by the marker interface
 * [BpmnTask], so every new task subtype that extends [BpmnTask] participates automatically.
 */
fun BpmnNode.isTask(): Boolean = this is BpmnTask
