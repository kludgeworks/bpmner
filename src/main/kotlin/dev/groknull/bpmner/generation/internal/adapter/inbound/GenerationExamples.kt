/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.adapter.inbound

import dev.groknull.bpmner.api.BpmnTimerKind
import dev.groknull.bpmner.api.DataFlowDirection
import dev.groknull.bpmner.domain.BpmnDataAssociation
import dev.groknull.bpmner.domain.BpmnDataObject
import dev.groknull.bpmner.domain.BpmnDataStore
import dev.groknull.bpmner.domain.BpmnEdge
import dev.groknull.bpmner.domain.BpmnLane
import dev.groknull.bpmner.domain.BpmnParticipant
import dev.groknull.bpmner.generation.FlatBpmnDefinition
import dev.groknull.bpmner.generation.FlatBpmnEventDefinition
import dev.groknull.bpmner.generation.FlatBpmnEventDefinitionKind
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

    const val DATA_ARTIFACTS_LABEL: String =
        "Data objects/stores: an activity reads a data store + data object and writes a data object; " +
            "each link is a READ/WRITE data association"

    const val SUB_PROCESS_LABEL: String =
        "Embedded subprocess: a SUB_PROCESS node on the main flow whose members carry parentRef = the " +
            "subprocess id; the subprocess has its own inner start/end and no flow crosses its boundary"

    const val EVENT_SUB_PROCESS_LABEL: String =
        "Event subprocess: a SUB_PROCESS node with triggeredByEvent=true and a typed inner start " +
            "(isInterrupting per the contract); members carry parentRef and it has no connecting flow"

    const val POOLS_AND_LANES_LABEL: String =
        "White-box pool with lanes: one participant whose processRef is the process and whose name is " +
            "the process name, plus one lane per performing role with that role's node ids in flowNodeRefs"

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

    private const val DATA_START = "StartEvent_1"
    private const val VALIDATE = "act-validate-order"
    private const val DATA_END = "end-validated"
    private const val ORDER = "DataObject_order"
    private const val VALIDATED = "DataObject_validated_order"
    private const val CUSTOMER_DB = "DataStore_customer"

    val dataArtifacts: FlatBpmnDefinition =
        FlatBpmnDefinition(
            processId = "Process_order_validation",
            processName = "Order validation",
            nodes = listOf(
                FlatBpmnNode(DATA_START, FlatBpmnNodeKind.START_EVENT, "Order received"),
                FlatBpmnNode(VALIDATE, FlatBpmnNodeKind.SERVICE_TASK, "Validate order"),
                FlatBpmnNode(DATA_END, FlatBpmnNodeKind.END_EVENT, "Order validated"),
            ),
            sequences = listOf(
                BpmnEdge("Flow_1", DATA_START, VALIDATE),
                BpmnEdge("Flow_2", VALIDATE, DATA_END),
            ),
            // Business-noun names only — no "activity"/"process"/"event" type words.
            dataObjects = listOf(
                BpmnDataObject(ORDER, "Order"),
                BpmnDataObject(VALIDATED, "Validated order"),
            ),
            dataStores = listOf(
                BpmnDataStore(CUSTOMER_DB, "Customer database"),
            ),
            dataAssociations = listOf(
                BpmnDataAssociation("DataAssoc_1", VALIDATE, ORDER, DataFlowDirection.READ),
                BpmnDataAssociation("DataAssoc_2", VALIDATE, CUSTOMER_DB, DataFlowDirection.READ),
                BpmnDataAssociation("DataAssoc_3", VALIDATE, VALIDATED, DataFlowDirection.WRITE),
            ),
        )

    // Embedded subprocess (matches ContractExtractionExamples.subProcessExample). The SUB_PROCESS
    // node sits on the main flow (Start → Assess claim → Pay claim → End); its three members and
    // their inner flow — including a self-contained inner start and end — all carry parentRef = the
    // subprocess id. No sequence flow crosses the boundary: the main flow connects to the subprocess
    // node itself, never to an inner member.
    private const val SUB_START = "StartEvent_1"
    private const val SUB_ASSESS = "sub-assess-claim"
    private const val SUB_INNER_START = "StartEvent_assess"
    private const val SUB_VALIDATE = "act-validate-documents"
    private const val SUB_ESTIMATE = "act-estimate-damage"
    private const val SUB_DECIDE = "act-decide-payout"
    private const val SUB_INNER_END = "EndEvent_assess"
    private const val SUB_PAY = "act-pay-claim"
    private const val SUB_END = "end-claim-paid"

    val embeddedSubProcess: FlatBpmnDefinition =
        FlatBpmnDefinition(
            processId = "Process_claim_assessment",
            processName = "Claim assessment",
            nodes = listOf(
                FlatBpmnNode(SUB_START, FlatBpmnNodeKind.START_EVENT, "Claim submitted"),
                FlatBpmnNode(SUB_ASSESS, FlatBpmnNodeKind.SUB_PROCESS, "Assess claim"),
                // Inner flow — every member carries parentRef = the subprocess id.
                FlatBpmnNode(SUB_INNER_START, FlatBpmnNodeKind.START_EVENT, parentRef = SUB_ASSESS),
                FlatBpmnNode(SUB_VALIDATE, FlatBpmnNodeKind.USER_TASK, "Validate documents", parentRef = SUB_ASSESS),
                FlatBpmnNode(SUB_ESTIMATE, FlatBpmnNodeKind.SERVICE_TASK, "Estimate damage", parentRef = SUB_ASSESS),
                FlatBpmnNode(SUB_DECIDE, FlatBpmnNodeKind.USER_TASK, "Decide payout", parentRef = SUB_ASSESS),
                FlatBpmnNode(SUB_INNER_END, FlatBpmnNodeKind.END_EVENT, parentRef = SUB_ASSESS),
                FlatBpmnNode(SUB_PAY, FlatBpmnNodeKind.SERVICE_TASK, "Pay claim"),
                FlatBpmnNode(SUB_END, FlatBpmnNodeKind.END_EVENT, "Claim paid"),
            ),
            sequences = listOf(
                // Main flow: connects to the subprocess node itself, never to an inner member.
                BpmnEdge("Flow_1", SUB_START, SUB_ASSESS),
                BpmnEdge("Flow_2", SUB_ASSESS, SUB_PAY),
                BpmnEdge("Flow_3", SUB_PAY, SUB_END),
                // Inner flow: both endpoints inside the subprocess; edges carry parentRef too.
                BpmnEdge("Flow_in_1", SUB_INNER_START, SUB_VALIDATE, parentRef = SUB_ASSESS),
                BpmnEdge("Flow_in_2", SUB_VALIDATE, SUB_ESTIMATE, parentRef = SUB_ASSESS),
                BpmnEdge("Flow_in_3", SUB_ESTIMATE, SUB_DECIDE, parentRef = SUB_ASSESS),
                BpmnEdge("Flow_in_4", SUB_DECIDE, SUB_INNER_END, parentRef = SUB_ASSESS),
            ),
        )

    // Event subprocess. The SUB_PROCESS node has triggeredByEvent=true and a typed inner start whose
    // eventDefinition matches the trigger (TIMER here) with isInterrupting=false (non-interrupting:
    // the escalation runs alongside the main flow). Members and inner edges carry parentRef = the
    // event-subprocess id, and the event subprocess has NO connecting flow on the main process — it
    // runs when its inner start fires. Main flow: Start → Review request → End.
    private const val ESP_MAIN_START = "StartEvent_1"
    private const val ESP_REVIEW = "act-review-request"
    private const val ESP_MAIN_END = "end-reviewed"
    private const val ESP_OVERDUE = "esp-escalate-overdue"
    private const val ESP_INNER_START = "StartEvent_overdue"
    private const val ESP_ESCALATE = "act-notify-manager"
    private const val ESP_INNER_END = "EndEvent_overdue"

    val eventSubProcess: FlatBpmnDefinition =
        FlatBpmnDefinition(
            processId = "Process_request_review",
            processName = "Request review",
            nodes = listOf(
                FlatBpmnNode(ESP_MAIN_START, FlatBpmnNodeKind.START_EVENT, "Request submitted"),
                FlatBpmnNode(ESP_REVIEW, FlatBpmnNodeKind.USER_TASK, "Review request"),
                FlatBpmnNode(ESP_MAIN_END, FlatBpmnNodeKind.END_EVENT, "Request reviewed"),
                // Event subprocess: triggeredByEvent=true, off the main flow.
                FlatBpmnNode(ESP_OVERDUE, FlatBpmnNodeKind.SUB_PROCESS, "Escalate if overdue", triggeredByEvent = true),
                // Typed inner start: TIMER eventDefinition, non-interrupting.
                FlatBpmnNode(
                    ESP_INNER_START,
                    FlatBpmnNodeKind.START_EVENT,
                    eventDefinition = FlatBpmnEventDefinition(
                        type = FlatBpmnEventDefinitionKind.TIMER,
                        timerKind = BpmnTimerKind.DURATION,
                        expression = "PT24H",
                    ),
                    isInterrupting = false,
                    parentRef = ESP_OVERDUE,
                ),
                FlatBpmnNode(ESP_ESCALATE, FlatBpmnNodeKind.SERVICE_TASK, "Notify manager", parentRef = ESP_OVERDUE),
                FlatBpmnNode(ESP_INNER_END, FlatBpmnNodeKind.END_EVENT, parentRef = ESP_OVERDUE),
            ),
            sequences = listOf(
                // Main flow only — the event subprocess has no connecting flow.
                BpmnEdge("Flow_1", ESP_MAIN_START, ESP_REVIEW),
                BpmnEdge("Flow_2", ESP_REVIEW, ESP_MAIN_END),
                // Inner handler flow, all parentRef'd to the event subprocess.
                BpmnEdge("Flow_esp_1", ESP_INNER_START, ESP_ESCALATE, parentRef = ESP_OVERDUE),
                BpmnEdge("Flow_esp_2", ESP_ESCALATE, ESP_INNER_END, parentRef = ESP_OVERDUE),
            ),
        )

    private const val POOL_PROC = "Process_order_handling"
    private const val POOL_PARTICIPANT = "Participant_order_handling"
    private const val POOL_START = "StartEvent_1"
    private const val POOL_CONFIRM = "act-confirm-order"
    private const val POOL_APPROVE = "act-approve-payment"
    private const val POOL_HANDLED = "end-handled"

    // A single white-box pool partitioned into two role lanes. Each lane lists the ids of the nodes
    // its actor performs; the participant's name matches the process name and its processRef binds
    // the pool to the process. No DI — the auto-layout stage places the swimlanes.
    val whiteBoxPoolWithLanes: FlatBpmnDefinition =
        FlatBpmnDefinition(
            processId = POOL_PROC,
            processName = "Order handling",
            nodes = listOf(
                FlatBpmnNode(POOL_START, FlatBpmnNodeKind.START_EVENT, "Order submitted"),
                FlatBpmnNode(POOL_CONFIRM, FlatBpmnNodeKind.USER_TASK, "Confirm order details"),
                FlatBpmnNode(POOL_APPROVE, FlatBpmnNodeKind.USER_TASK, "Approve payment"),
                FlatBpmnNode(POOL_HANDLED, FlatBpmnNodeKind.END_EVENT, "Order handled"),
            ),
            sequences = listOf(
                BpmnEdge("Flow_1", POOL_START, POOL_CONFIRM),
                BpmnEdge("Flow_2", POOL_CONFIRM, POOL_APPROVE),
                BpmnEdge("Flow_3", POOL_APPROVE, POOL_HANDLED),
            ),
            participants = listOf(
                BpmnParticipant(POOL_PARTICIPANT, "Order handling", processRef = POOL_PROC),
            ),
            lanes = listOf(
                BpmnLane(
                    "Lane_sales",
                    "Sales",
                    participantId = POOL_PARTICIPANT,
                    flowNodeRefs = listOf(POOL_START, POOL_CONFIRM),
                ),
                BpmnLane(
                    "Lane_finance",
                    "Finance",
                    participantId = POOL_PARTICIPANT,
                    flowNodeRefs = listOf(POOL_APPROVE, POOL_HANDLED),
                ),
            ),
        )

    /** Every worked example with its label, attached to the generation call in order. */
    val all: List<Pair<String, FlatBpmnDefinition>> = listOf(
        PARALLEL_LABEL to parallelForkJoin,
        INCLUSIVE_LABEL to inclusiveWithDefault,
        DATA_ARTIFACTS_LABEL to dataArtifacts,
        SUB_PROCESS_LABEL to embeddedSubProcess,
        EVENT_SUB_PROCESS_LABEL to eventSubProcess,
        POOLS_AND_LANES_LABEL to whiteBoxPoolWithLanes,
    )
}
