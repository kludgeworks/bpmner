/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain

import dev.groknull.bpmner.api.BpmnDefinition
import dev.groknull.bpmner.api.BpmnEvent
import dev.groknull.bpmner.api.BpmnUnrecognizedEventDefinition
import dev.groknull.bpmner.api.BpmnUnrecognizedNode
import dev.groknull.bpmner.api.RepairKind
import dev.groknull.bpmner.validation.BpmnDiagnostic
import dev.groknull.bpmner.validation.BpmnDiagnosticSeverity
import dev.groknull.bpmner.validation.BpmnDiagnosticSource

/**
 * Pre-flight scanner for unrecognized BPMN elements at the LLM repair boundary.
 *
 * #282's parser-as-structure refactor lets [BpmnUnrecognizedNode] and
 * [BpmnUnrecognizedEventDefinition] flow into [BpmnDefinition]. Both are deliberately absent
 * from the `@JsonSubTypes` registration on `core.BpmnNode` / `core.BpmnEventDefinition`, so a
 * definition carrying either fails Jackson serialization. The repair pipeline serializes the
 * definition in three places (`BpmnRepairPromptFactory.initialMessages` /
 * `patchFeedback` / `fullRepairFeedback`) plus indirectly via
 * `BpmnFingerprintService.definitionFingerprint`, so an unrecognized element reaching repair
 * surfaces as an unhandled `InvalidDefinitionException`.
 *
 * This scanner is the explicit pre-flight contract: [scan] is called at the top of
 * `BpmnRepairAgent.validate` and short-circuits repair into a typed UNFIXABLE diagnostic
 * before any Jackson-touching code runs. The `@JsonSubTypes` omission stays as
 * defense-in-depth (the prompt factory's `require()` guards rely on it for naming).
 */
internal object BpmnUnrecognizedElementScanner {
    const val RULE_ID: String = "repair-unsupported-element"

    fun scan(definition: BpmnDefinition): List<UnrecognizedFinding> = buildList {
        definition.nodes.forEach { node ->
            if (node is BpmnUnrecognizedNode) {
                add(UnrecognizedFinding.Node(id = node.id, bpmnType = node.bpmnType))
            }
            if (node is BpmnEvent && node.eventDefinition is BpmnUnrecognizedEventDefinition) {
                val ed = node.eventDefinition as BpmnUnrecognizedEventDefinition
                add(UnrecognizedFinding.EventDefinition(eventId = node.id, typeName = ed.typeName))
            }
        }
    }
}

/**
 * One unrecognized element detected by [BpmnUnrecognizedElementScanner.scan].
 *
 * [Node] is a fallback process element (e.g. `bpmn:Choreography`). [EventDefinition] is an
 * unsupported event-definition typename (e.g. `bpmn:CompensateEventDefinition`) attached to
 * an otherwise typed event node.
 */
internal sealed interface UnrecognizedFinding {
    fun toDiagnostic(): BpmnDiagnostic

    data class Node(val id: String, val bpmnType: String) : UnrecognizedFinding {
        override fun toDiagnostic(): BpmnDiagnostic = BpmnDiagnostic(
            source = BpmnDiagnosticSource.LINT,
            message = "Unsupported BPMN element type '$bpmnType' on '$id'. " +
                "Remove the element; bpmner does not support this element type.",
            severity = BpmnDiagnosticSeverity.ERROR,
            rule = BpmnUnrecognizedElementScanner.RULE_ID,
            elementId = id,
            kind = RepairKind.UNFIXABLE,
        )
    }

    data class EventDefinition(val eventId: String, val typeName: String) : UnrecognizedFinding {
        override fun toDiagnostic(): BpmnDiagnostic = BpmnDiagnostic(
            source = BpmnDiagnosticSource.LINT,
            message = "Unsupported BPMN event definition '$typeName' on event '$eventId'. " +
                "Remove the event definition; bpmner does not support this event definition type.",
            severity = BpmnDiagnosticSeverity.ERROR,
            rule = BpmnUnrecognizedElementScanner.RULE_ID,
            elementId = eventId,
            kind = RepairKind.UNFIXABLE,
        )
    }
}
