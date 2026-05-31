/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.adapter.inbound

import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.generation.FlatBpmnDefinition
import dev.groknull.bpmner.generation.FlatBpmnNode
import dev.groknull.bpmner.generation.FlatBpmnNodeKind

/**
 * Typed few-shot examples attached to the BPMN-generation call via
 * `Creating<FlatBpmnDefinition>.withExample(...)`. They teach the two non-obvious topologies the
 * LLM does not reliably reproduce from training: the PARALLEL fork/join (every branch fires; the
 * synthesised join waits for all of them) and the INCLUSIVE fork with a DEFAULT branch
 * (independent conditions; the join waits only for the branches that fired).
 *
 * As typed values the compiler keeps them structurally valid as the schema evolves, and the
 * framework renders them into the prompt in the same JSON shape the LLM must emit. Node ids are
 * named constants so each edge endpoint resolves to a declared node — a typo is a compile error.
 */
internal object GenerationExamples {
    const val PARALLEL_LABEL: String =
        "PARALLEL fork/join: every branch runs concurrently; a synthesised join (no name) waits for all of them"

    const val INCLUSIVE_LABEL: String =
        "INCLUSIVE fork with a DEFAULT branch: conditions are independent; the join waits only for the branches that fired"

    private const val START = "StartEvent_1"

    private const val PREP_FORK = "dec-prep-tracks"
    private const val PREP_IT = "act-prep-it"
    private const val PREP_FACILITIES = "act-prep-facilities"
    private const val PREP_MANAGER = "act-prep-manager"
    private const val PREP_JOIN = "Gateway_join_prep"
    private const val ORIENTATION = "act-orientation"
    private const val ONBOARDED = "end-onboarded"

    private const val EXTRAS_FORK = "dec-extras"
    private const val WRAP = "act-wrap"
    private const val INSERT = "act-insert"
    private const val SKIP = "act-skip"
    private const val EXTRAS_JOIN = "Gateway_join_extras"
    private const val LABEL = "act-label"
    private const val PACKED = "end-packed"

    val parallelForkJoin: FlatBpmnDefinition =
        FlatBpmnDefinition(
            processId = "Process_onboarding",
            processName = "Employee onboarding",
            nodes = listOf(
                FlatBpmnNode(START, FlatBpmnNodeKind.START_EVENT, "Onboarding started"),
                FlatBpmnNode(PREP_FORK, FlatBpmnNodeKind.PARALLEL_GATEWAY, "Run preparation tracks"),
                FlatBpmnNode(PREP_IT, FlatBpmnNodeKind.USER_TASK, "Prepare IT equipment"),
                FlatBpmnNode(PREP_FACILITIES, FlatBpmnNodeKind.USER_TASK, "Prepare desk space"),
                FlatBpmnNode(PREP_MANAGER, FlatBpmnNodeKind.USER_TASK, "Brief the manager"),
                // Converging join carries no name.
                FlatBpmnNode(PREP_JOIN, FlatBpmnNodeKind.PARALLEL_GATEWAY),
                FlatBpmnNode(ORIENTATION, FlatBpmnNodeKind.USER_TASK, "Run orientation"),
                FlatBpmnNode(ONBOARDED, FlatBpmnNodeKind.END_EVENT, "Employee onboarded"),
            ),
            sequences = listOf(
                BpmnEdge("Flow_1", START, PREP_FORK),
                BpmnEdge("Flow_2", PREP_FORK, PREP_IT),
                BpmnEdge("Flow_3", PREP_FORK, PREP_FACILITIES),
                BpmnEdge("Flow_4", PREP_FORK, PREP_MANAGER),
                BpmnEdge("Flow_5", PREP_IT, PREP_JOIN),
                BpmnEdge("Flow_6", PREP_FACILITIES, PREP_JOIN),
                BpmnEdge("Flow_7", PREP_MANAGER, PREP_JOIN),
                BpmnEdge("Flow_8", PREP_JOIN, ORIENTATION),
                BpmnEdge("Flow_9", ORIENTATION, ONBOARDED),
            ),
        )

    val inclusiveWithDefault: FlatBpmnDefinition =
        FlatBpmnDefinition(
            processId = "Process_fulfilment",
            processName = "Order fulfilment add-ons",
            nodes = listOf(
                FlatBpmnNode(START, FlatBpmnNodeKind.START_EVENT, "Order ready to pack"),
                FlatBpmnNode(EXTRAS_FORK, FlatBpmnNodeKind.INCLUSIVE_GATEWAY, "Which add-ons apply?"),
                FlatBpmnNode(WRAP, FlatBpmnNodeKind.USER_TASK, "Add gift wrap"),
                FlatBpmnNode(INSERT, FlatBpmnNodeKind.USER_TASK, "Add promotional insert"),
                FlatBpmnNode(SKIP, FlatBpmnNodeKind.SERVICE_TASK, "Skip add-ons"),
                FlatBpmnNode(EXTRAS_JOIN, FlatBpmnNodeKind.INCLUSIVE_GATEWAY),
                FlatBpmnNode(LABEL, FlatBpmnNodeKind.SERVICE_TASK, "Print shipping label"),
                FlatBpmnNode(PACKED, FlatBpmnNodeKind.END_EVENT, "Order packed"),
            ),
            sequences = listOf(
                BpmnEdge("Flow_1", START, EXTRAS_FORK),
                BpmnEdge("Flow_2", EXTRAS_FORK, WRAP, conditionExpression = "gift wrap requested"),
                BpmnEdge("Flow_3", EXTRAS_FORK, INSERT, conditionExpression = "order qualifies for insert"),
                // DEFAULT branch: no condition, isDefault = true; the renderer writes the gateway's `default`.
                BpmnEdge("Flow_4", EXTRAS_FORK, SKIP, isDefault = true),
                BpmnEdge("Flow_5", WRAP, EXTRAS_JOIN),
                BpmnEdge("Flow_6", INSERT, EXTRAS_JOIN),
                BpmnEdge("Flow_7", SKIP, EXTRAS_JOIN),
                BpmnEdge("Flow_8", EXTRAS_JOIN, LABEL),
                BpmnEdge("Flow_9", LABEL, PACKED),
            ),
        )
}
