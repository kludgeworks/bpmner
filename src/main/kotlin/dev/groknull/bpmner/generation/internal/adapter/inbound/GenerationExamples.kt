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
 * `Creating<FlatBpmnDefinition>.withExample(...)` (#309 follow-up to #300). These replace the two
 * inline JSON worked examples that previously lived in `generate_bpmn.jinja`: they teach the two
 * non-obvious topologies the LLM does not reliably reproduce from training — the PARALLEL
 * fork/join (every branch fires, the synthesised join waits for all) and the INCLUSIVE fork with
 * a DEFAULT branch (independent conditions, the join waits only for activated branches).
 *
 * Keeping them as typed values (rather than prompt prose) means the compiler guarantees they stay
 * structurally valid as the schema evolves, and the framework renders them into the prompt in the
 * same JSON shape the LLM must emit.
 */
internal object GenerationExamples {
    const val PARALLEL_LABEL: String =
        "PARALLEL fork/join: every branch runs concurrently; a synthesised join (no name) waits for all of them"

    const val INCLUSIVE_LABEL: String =
        "INCLUSIVE fork with a DEFAULT branch: conditions are independent; the join waits only for the branches that fired"

    val parallelForkJoin: FlatBpmnDefinition =
        FlatBpmnDefinition(
            processId = "Process_onboarding",
            processName = "Employee onboarding",
            nodes = listOf(
                FlatBpmnNode("StartEvent_1", FlatBpmnNodeKind.START_EVENT, "Onboarding started"),
                FlatBpmnNode("dec-prep-tracks", FlatBpmnNodeKind.PARALLEL_GATEWAY, "Run preparation tracks"),
                FlatBpmnNode("act-prep-it", FlatBpmnNodeKind.USER_TASK, "Prepare IT equipment"),
                FlatBpmnNode("act-prep-facilities", FlatBpmnNodeKind.USER_TASK, "Prepare desk space"),
                FlatBpmnNode("act-prep-manager", FlatBpmnNodeKind.USER_TASK, "Brief the manager"),
                // Synthesised converging join — no name (converging gateways are unnamed).
                FlatBpmnNode("Gateway_join_prep", FlatBpmnNodeKind.PARALLEL_GATEWAY),
                FlatBpmnNode("act-orientation", FlatBpmnNodeKind.USER_TASK, "Run orientation"),
                FlatBpmnNode("end-onboarded", FlatBpmnNodeKind.END_EVENT, "Employee onboarded"),
            ),
            sequences = listOf(
                BpmnEdge("Flow_1", "StartEvent_1", "dec-prep-tracks"),
                BpmnEdge("Flow_2", "dec-prep-tracks", "act-prep-it"),
                BpmnEdge("Flow_3", "dec-prep-tracks", "act-prep-facilities"),
                BpmnEdge("Flow_4", "dec-prep-tracks", "act-prep-manager"),
                BpmnEdge("Flow_5", "act-prep-it", "Gateway_join_prep"),
                BpmnEdge("Flow_6", "act-prep-facilities", "Gateway_join_prep"),
                BpmnEdge("Flow_7", "act-prep-manager", "Gateway_join_prep"),
                BpmnEdge("Flow_8", "Gateway_join_prep", "act-orientation"),
                BpmnEdge("Flow_9", "act-orientation", "end-onboarded"),
            ),
        )

    val inclusiveWithDefault: FlatBpmnDefinition =
        FlatBpmnDefinition(
            processId = "Process_fulfilment",
            processName = "Order fulfilment add-ons",
            nodes = listOf(
                FlatBpmnNode("StartEvent_1", FlatBpmnNodeKind.START_EVENT, "Order ready to pack"),
                FlatBpmnNode("dec-extras", FlatBpmnNodeKind.INCLUSIVE_GATEWAY, "Which add-ons apply?"),
                FlatBpmnNode("act-wrap", FlatBpmnNodeKind.USER_TASK, "Add gift wrap"),
                FlatBpmnNode("act-insert", FlatBpmnNodeKind.USER_TASK, "Add promotional insert"),
                FlatBpmnNode("act-skip", FlatBpmnNodeKind.SERVICE_TASK, "Skip add-ons"),
                FlatBpmnNode("Gateway_join_extras", FlatBpmnNodeKind.INCLUSIVE_GATEWAY),
                FlatBpmnNode("act-label", FlatBpmnNodeKind.SERVICE_TASK, "Print shipping label"),
                FlatBpmnNode("end-packed", FlatBpmnNodeKind.END_EVENT, "Order packed"),
            ),
            sequences = listOf(
                BpmnEdge("Flow_1", "StartEvent_1", "dec-extras"),
                BpmnEdge("Flow_2", "dec-extras", "act-wrap", conditionExpression = "gift wrap requested"),
                BpmnEdge("Flow_3", "dec-extras", "act-insert", conditionExpression = "order qualifies for insert"),
                // DEFAULT branch: no condition, isDefault=true (the renderer writes the gateway's `default`).
                BpmnEdge("Flow_4", "dec-extras", "act-skip", isDefault = true),
                BpmnEdge("Flow_5", "act-wrap", "Gateway_join_extras"),
                BpmnEdge("Flow_6", "act-insert", "Gateway_join_extras"),
                BpmnEdge("Flow_7", "act-skip", "Gateway_join_extras"),
                BpmnEdge("Flow_8", "Gateway_join_extras", "act-label"),
                BpmnEdge("Flow_9", "act-label", "end-packed"),
            ),
        )
}
