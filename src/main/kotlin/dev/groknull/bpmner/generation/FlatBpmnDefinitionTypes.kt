/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import dev.groknull.bpmner.bpmn.BpmnAssociation
import dev.groknull.bpmner.bpmn.BpmnDataAssociation
import dev.groknull.bpmner.bpmn.BpmnDataObject
import dev.groknull.bpmner.bpmn.BpmnDataStore
import dev.groknull.bpmner.bpmn.BpmnEdge
import dev.groknull.bpmner.bpmn.BpmnErrorRef
import dev.groknull.bpmner.bpmn.BpmnEscalationRef
import dev.groknull.bpmner.bpmn.BpmnGroup
import dev.groknull.bpmner.bpmn.BpmnLane
import dev.groknull.bpmner.bpmn.BpmnMessageFlow
import dev.groknull.bpmner.bpmn.BpmnMessageRef
import dev.groknull.bpmner.bpmn.BpmnParticipant
import dev.groknull.bpmner.bpmn.BpmnSignalRef
import dev.groknull.bpmner.bpmn.BpmnTextAnnotation
import dev.groknull.bpmner.bpmn.BpmnTimerKind
import dev.groknull.bpmner.bpmn.MultiInstanceMode
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size

/*
 * Flat wire-format types that the LLM is asked to produce for BPMN generation and
 * full-rewrite repair. Each sealed hierarchy in BpmnDomain.kt collapses to a single
 * data class with an enum `type` discriminator and nullable kind-specific fields.
 *
 * Discriminator field is named `type` (not `kind`) to match the existing sealed
 * @JsonTypeInfo(property = "type") wire shape; this preserves prompt prose
 * (BpmnContractGenerationPromptFactory) verbatim and avoids any prompt-cache
 * invalidation from a key rename.
 *
 * These types are an LLM-adapter implementation detail. Downstream packages keep
 * using the sealed BpmnDefinition from core/BpmnDomain.kt; conversion happens in
 * FlatBpmnDefinitionMapper.kt at the agent boundary.
 */

public enum class FlatBpmnNodeKind {
    START_EVENT,
    END_EVENT,
    BOUNDARY_EVENT,
    USER_TASK,
    SERVICE_TASK,
    SCRIPT_TASK,
    BUSINESS_RULE_TASK,
    SEND_TASK,
    RECEIVE_TASK,
    MANUAL_TASK,
    EXCLUSIVE_GATEWAY,
    INCLUSIVE_GATEWAY,
    PARALLEL_GATEWAY,
    EVENT_BASED_GATEWAY,
    INTERMEDIATE_CATCH_EVENT,
    INTERMEDIATE_THROW_EVENT,
    SUB_PROCESS,
    CALL_ACTIVITY,
}

public enum class FlatBpmnEventDefinitionKind {
    NONE,
    TIMER,
    MESSAGE,
    SIGNAL,
    ERROR,
    ESCALATION,
    TERMINATE,
}

@JsonClassDescription(
    "BPMN node with semantic type. Set `type` and populate the matching kind-specific fields: " +
        "BUSINESS_RULE_TASK → decisionRef; SEND_TASK / RECEIVE_TASK → messageRef; " +
        "START_EVENT / END_EVENT → optional eventDefinition (defaults to NONE); " +
        "INTERMEDIATE_CATCH_EVENT / INTERMEDIATE_THROW_EVENT → required eventDefinition; " +
        "BOUNDARY_EVENT → attachedToRef + eventDefinition (cancelActivity defaults true); " +
        "START_EVENT additionally accepts isInterrupting (defaults true); " +
        "SUB_PROCESS → optional triggeredByEvent (defaults false), with inner nodes carrying parentRef; " +
        "CALL_ACTIVITY → required calledElement (id of the separately-defined process it invokes).",
)
public data class FlatBpmnNode(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    val id: String,
    @get:JsonPropertyDescription(
        "Node semantic kind. Drives which kind-specific fields the LLM must populate; see the " +
            "class description for the per-type field rules.",
    )
    val type: FlatBpmnNodeKind,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    val name: String? = null,
    @field:Valid
    @get:JsonPropertyDescription(
        "Nested BPMN event definition for event-position nodes (START_EVENT, END_EVENT, " +
            "INTERMEDIATE_*, BOUNDARY_EVENT). Defaults to NONE for START/END if omitted; required " +
            "for INTERMEDIATE_* and BOUNDARY_EVENT.",
    )
    val eventDefinition: FlatBpmnEventDefinition? = null,
    @get:JsonPropertyDescription(
        "START_EVENT only. Whether this start interrupts its enclosing scope; event-subprocess " +
            "starts may set false. Defaults to true if omitted.",
    )
    val isInterrupting: Boolean? = null,
    @get:JsonPropertyDescription(
        "BUSINESS_RULE_TASK only. Identifier of the decision (DMN decision id, rule-set name) " +
            "the task evaluates. Free-form string; non-blank.",
    )
    val decisionRef: String? = null,
    @get:JsonPropertyDescription(
        "SEND_TASK / RECEIVE_TASK only. Id of the BpmnMessageRef in the process-level message " +
            "catalogue that this task emits (SEND) or waits for (RECEIVE).",
    )
    val messageRef: String? = null,
    @get:JsonPropertyDescription(
        "BOUNDARY_EVENT only. BPMN id of the activity this boundary event is attached to.",
    )
    val attachedToRef: String? = null,
    @get:JsonPropertyDescription(
        "BOUNDARY_EVENT only. Whether the boundary event cancels the attached activity when it " +
            "fires. Defaults to true if omitted.",
    )
    val cancelActivity: Boolean? = null,
    @field:Valid
    @get:JsonPropertyDescription(
        "Task kinds only. Set when the activity runs once per item in a collection (a 'for each …' " +
            "loop). Pair it with a linked text annotation (see definition-level annotations / " +
            "associations) describing the item set. Leave null for an ordinary single-run activity.",
    )
    val multiInstance: FlatMultiInstanceLoopCharacteristics? = null,
    @field:Valid
    @get:JsonPropertyDescription(
        "Task kinds only. Set when the activity repeats until a condition is met (a while/until/" +
            "retry loop). Pair it with a linked text annotation describing the loop condition. " +
            "Leave null for an ordinary single-run activity.",
    )
    val standardLoop: FlatStandardLoopCharacteristics? = null,
    @get:JsonPropertyDescription(
        "SUB_PROCESS only. false for an ordinary embedded subprocess; true for an event subprocess. " +
            "Defaults to false if omitted.",
    )
    val triggeredByEvent: Boolean? = null,
    @get:JsonPropertyDescription(
        "CALL_ACTIVITY only. Id of the separately-defined process this call activity invokes. The " +
            "called process is referenced by id and need not appear in this definition.",
    )
    val calledElement: String? = null,
    @get:JsonPropertyDescription(
        "Id of the enclosing SUB_PROCESS when this node is nested inside one; leave null for a " +
            "top-level node. Nodes stay in this flat list and carry the back-reference.",
    )
    val parentRef: String? = null,
)

@JsonClassDescription(
    "Multi-instance loop characteristics for a task that runs once per item in a collection.",
)
public data class FlatMultiInstanceLoopCharacteristics(
    @get:JsonPropertyDescription(
        "SEQUENTIAL = items one at a time (isSequential=true); PARALLEL = items concurrently " +
            "(isSequential=false).",
    )
    val mode: MultiInstanceMode,
    @field:NotBlank
    @get:JsonPropertyDescription(
        "Human-readable description of the collection iterated over, e.g. \"each line item on the slip\".",
    )
    val collectionDescription: String,
    @get:JsonPropertyDescription("Optional fixed iteration count when statically known")
    val loopCardinality: Int? = null,
    @get:JsonPropertyDescription("Optional early-exit predicate that stops iteration before all items are done")
    val completionCondition: String? = null,
)

@JsonClassDescription(
    "Standard loop characteristics for a task that repeats until a condition is met.",
)
public data class FlatStandardLoopCharacteristics(
    @get:JsonPropertyDescription(
        "true = while-loop (condition tested before each iteration); " +
            "false = until-loop (body runs once, then the condition is tested).",
    )
    val testBefore: Boolean = true,
    @field:Size(max = 500)
    @get:JsonPropertyDescription("Human-readable loop continue/exit condition, e.g. \"payment not yet successful\".")
    val loopCondition: String? = null,
    @get:JsonPropertyDescription("Optional cap on the number of iterations, e.g. retry up to 3 times")
    val loopMaximum: Int? = null,
)

@JsonClassDescription(
    "Reusable BPMN event definition carried by an event-position node. Set `type` and populate " +
        "the matching kind-specific fields: TIMER → timerKind + expression; MESSAGE → messageRef; " +
        "SIGNAL → signalRef; ERROR → errorRef; ESCALATION → escalationRef. NONE and TERMINATE " +
        "carry no payload.",
)
public data class FlatBpmnEventDefinition(
    @get:JsonPropertyDescription(
        "Event-definition kind. NONE = plain (no payload); TERMINATE = end-only terminate; " +
            "TIMER = populate timerKind + expression; MESSAGE = populate messageRef; SIGNAL = " +
            "populate signalRef; ERROR = populate errorRef; ESCALATION = populate escalationRef.",
    )
    val type: FlatBpmnEventDefinitionKind,
    @get:JsonPropertyDescription("Required when type=TIMER. DATE / DURATION / CYCLE.")
    val timerKind: BpmnTimerKind? = null,
    @get:JsonPropertyDescription(
        "Required when type=TIMER. ISO-8601 date, duration, or cron-like schedule expression.",
    )
    val expression: String? = null,
    @get:JsonPropertyDescription(
        "Required when type=MESSAGE. Id of the BpmnMessageRef in the process-level message catalogue.",
    )
    val messageRef: String? = null,
    @get:JsonPropertyDescription(
        "Required when type=SIGNAL. Id of the BpmnSignalRef in the process-level signal catalogue.",
    )
    val signalRef: String? = null,
    @get:JsonPropertyDescription(
        "Required when type=ERROR. Id of the BpmnErrorRef in the process-level error catalogue.",
    )
    val errorRef: String? = null,
    @get:JsonPropertyDescription(
        "Required when type=ESCALATION. Id of the BpmnEscalationRef in the process-level " +
            "escalation catalogue.",
    )
    val escalationRef: String? = null,
)

@JsonClassDescription("Typed BPMN process definition describing the semantic topology of a workflow")
public data class FlatBpmnDefinition(
    @field:NotBlank
    @get:JsonPropertyDescription("Stable BPMN process id, e.g. Process_1")
    val processId: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Human-readable BPMN process name")
    val processName: String,
    @field:NotEmpty
    @field:Valid
    @get:JsonPropertyDescription("All BPMN nodes participating in the process graph")
    val nodes: List<FlatBpmnNode>,
    @field:NotEmpty
    @field:Valid
    @get:JsonPropertyDescription("Directed sequence-flow edges connecting node ids")
    val sequences: List<BpmnEdge>,
    @field:Valid
    @get:JsonPropertyDescription(
        "Process-level message catalogue: declare one BpmnMessageRef per distinct message name. " +
            "Every send/receive task and message event that uses that name references the same entry " +
            "by id — never duplicate. Use a stable id derived from the name, e.g. Message_OrderConfirmed.",
    )
    val messages: List<BpmnMessageRef> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription(
        "Process-level signal catalogue: declare one BpmnSignalRef per distinct signal name; every " +
            "signal event that uses that name references the same entry by id. Stable id e.g. " +
            "Signal_SettlementComplete.",
    )
    val signals: List<BpmnSignalRef> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription(
        "Process-level error catalogue: declare one BpmnErrorRef per distinct error code; every error " +
            "event that uses that code references the same entry by id. Stable id e.g. Error_CreditRejected.",
    )
    val errors: List<BpmnErrorRef> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription(
        "Process-level escalation catalogue: declare one BpmnEscalationRef per distinct escalation code; " +
            "every escalation event that uses that code references the same entry by id. Stable id e.g. " +
            "Escalation_ApprovalOverdue.",
    )
    val escalations: List<BpmnEscalationRef> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription(
        "Text annotations explaining elements. Required for every multi-instance task: emit one " +
            "describing the item set (\"For each …\") and link it to the task with an association.",
    )
    val annotations: List<BpmnTextAnnotation> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription("Visual BPMN group artifacts. Groups carry no process semantics.")
    val groups: List<BpmnGroup> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription(
        "Association edges linking each text annotation to the element it explains. For a " +
            "multi-instance task's annotation, set sourceRef=task id, targetRef=annotation id.",
    )
    val associations: List<BpmnAssociation> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription(
        "Data objects (transient information) flowing through the process, e.g. \"Order\". Link them " +
            "to activities via dataAssociations.",
    )
    val dataObjects: List<BpmnDataObject> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription(
        "Data stores (persisted information: databases, files, queues) the process reads or writes, " +
            "e.g. \"Customer database\". Link them to activities via dataAssociations.",
    )
    val dataStores: List<BpmnDataStore> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription(
        "Read/write links between activities and data objects/stores. Set sourceRef=activity id, " +
            "targetRef=data id, direction=READ (activity consumes) or WRITE (activity produces).",
    )
    val dataAssociations: List<BpmnDataAssociation> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription(
        "Participants (pools): a white-box participant sets processRef to this process id and owns its " +
            "lanes; a black-box participant (processRef null) is an external entity. Emit only when the " +
            "process is modelled as a collaboration.",
    )
    val participants: List<BpmnParticipant> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription(
        "Lanes partitioning a white-box pool by business role/performer. Set participantId to the owning " +
            "participant and flowNodeRefs to the ids of the nodes in the lane.",
    )
    val lanes: List<BpmnLane> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription(
        "Message flows between participants (across pools only). Set sourceRef/targetRef to a flow " +
            "element id or a black-box participant id; name them after the message, not an action.",
    )
    val messageFlows: List<BpmnMessageFlow> = emptyList(),
)

// Ported verbatim from core/BpmnDomain.kt:264-271 (file-private there; copying is cleaner than
// widening core visibility just to share a constant).
private const val NODE_ID_DESCRIPTION: String =
    "Unique node id. For contract-realized nodes, use the corresponding `act-…` / `dec-…` / `end-…` " +
        "id from the ProcessContract verbatim. For synthesized routing nodes (process start event, " +
        "converging joins, intermediate routing), use a stable unique id of your choosing (e.g. " +
        "`StartEvent_1`, `Gateway_join_1`). The element kind is carried by `type`, not the id prefix."

private const val NODE_NAME_DESCRIPTION: String =
    "Optional node label. Required for tasks, events, and diverging gateways; omit for converging gateways."
