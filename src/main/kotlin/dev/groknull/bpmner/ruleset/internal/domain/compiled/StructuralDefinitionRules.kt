/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset.internal.domain.compiled

import dev.groknull.bpmner.bpmn.BpmnAdHocSubProcess
import dev.groknull.bpmner.bpmn.BpmnComplexGateway
import dev.groknull.bpmner.bpmn.BpmnDefinitionContext
import dev.groknull.bpmner.bpmn.BpmnEndEvent
import dev.groknull.bpmner.bpmn.BpmnGateway
import dev.groknull.bpmner.bpmn.BpmnNoneEventDefinition
import dev.groknull.bpmner.bpmn.BpmnParallelGateway
import dev.groknull.bpmner.bpmn.BpmnRule
import dev.groknull.bpmner.bpmn.BpmnStartEvent
import dev.groknull.bpmner.bpmn.BpmnSubProcess
import dev.groknull.bpmner.bpmn.BpmnTerminateEventDefinition
import dev.groknull.bpmner.bpmn.RepairKind
import dev.groknull.bpmner.bpmn.RepairMetadata
import dev.groknull.bpmner.bpmn.RepairSafety
import dev.groknull.bpmner.bpmn.RuleCategory
import dev.groknull.bpmner.bpmn.RuleDiagnostic
import dev.groknull.bpmner.bpmn.RuleMetadata
import dev.groknull.bpmner.bpmn.RuleSeverity
import org.springframework.stereotype.Component

private fun metadata(
    id: String,
    name: String,
    intent: String,
    guidance: String,
    targets: List<String>,
) = RuleMetadata(
    id = id,
    name = name,
    slug = id.removePrefix("def-"),
    category = RuleCategory.Definition,
    intent = intent,
    forModellers = guidance,
    forAI = guidance,
    targetElements = targets,
    errorMessages = mapOf("default" to intent),
    severity = RuleSeverity.ERROR,
    repair = RepairMetadata(kind = RepairKind.LLM_MODEL_PATCH, safety = RepairSafety.LLM_ONLY),
)

@Component
internal class DuplicateSequenceFlowsRule : BpmnRule {
    override val id = "def-duplicate-sequence-flows"
    override val metadata = metadata(
        id,
        "Duplicate Sequence Flows",
        "Avoid duplicate sequence flows between the same source and target.",
        "Keep one sequence flow for each source/target pair in a scope.",
        listOf("bpmn:SequenceFlow"),
    )
    override fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic> = ctx.definition.sequences
        .groupBy { Triple(it.parentRef, it.sourceRef, it.targetRef) }
        .filterValues { it.size > 1 }
        .values.flatten().map { edge ->
            RuleDiagnostic(
                "def-duplicate-sequence-flow",
                id,
                RuleSeverity.ERROR,
                "duplicate sequence flow from ${edge.sourceRef} to ${edge.targetRef}",
                edge.id,
            )
        }
}

@Component
internal class ScopeRequiredEventsRule : BpmnRule {
    override val id = "def-scope-required-events"
    override val metadata = metadata(
        id,
        "Scope Required Events",
        "Ensure every embedded subprocess has a start and end event.",
        "Add at least one start event and end event inside every embedded subprocess.",
        listOf("bpmn:SubProcess", "bpmn:StartEvent", "bpmn:EndEvent"),
    )
    override fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic> = ctx.definition.nodes
        .filter { it is BpmnSubProcess || it is BpmnAdHocSubProcess }
        .flatMap { scope ->
            val children = ctx.definition.nodes.filter { it.parentRef == scope.id }
            buildList {
                if (children.none { it is BpmnStartEvent }) {
                    add(
                        RuleDiagnostic(
                            "def-scope-missing-start-event",
                            id,
                            RuleSeverity.ERROR,
                            "subprocess '${scope.id}' must contain at least one START_EVENT",
                            scope.id,
                        ),
                    )
                }
                if (children.none { it is BpmnEndEvent }) {
                    add(
                        RuleDiagnostic(
                            "def-scope-missing-end-event",
                            id,
                            RuleSeverity.ERROR,
                            "subprocess '${scope.id}' must contain at least one END_EVENT",
                            scope.id,
                        ),
                    )
                }
            }
        }
}

@Component
internal class EventStructureRule : BpmnRule {
    override val id = "def-event-structure"
    override val metadata = metadata(
        id,
        "Event Structure",
        "Enforce event flow cardinality, event-subprocess start, and terminate-end constraints.",
        "Do not connect flow into start events or out of end events. Use one triggering start event " +
            "in an event subprocess and reserve terminate end events for scopes with another completion path.",
        listOf("bpmn:StartEvent", "bpmn:EndEvent"),
    )
    override fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic> = buildList {
        addAll(eventCardinalityDiagnostics(ctx))
        ctx.definition.nodes
            .filterIsInstance<BpmnStartEvent>()
            .filter { it.isEventSubProcessStart }
            .forEach { start ->
                if (start.eventDefinition is BpmnNoneEventDefinition) {
                    add(
                        RuleDiagnostic(
                            "evt-event-subprocess-start-trigger",
                            id,
                            RuleSeverity.ERROR,
                            "event subprocess start event must declare a triggering event definition",
                            start.id,
                        ),
                    )
                }
                if ((ctx.incomingCounts[start.id] ?: 0) > 0) {
                    add(
                        RuleDiagnostic(
                            "evt-event-subprocess-start-incoming",
                            id,
                            RuleSeverity.ERROR,
                            "event subprocess start event must not have incoming sequence flow",
                            start.id,
                        ),
                    )
                }
            }
        ctx.definition.nodes
            .filterIsInstance<BpmnEndEvent>()
            .filter { it.eventDefinition is BpmnTerminateEventDefinition }
            .forEach { end ->
                val peers = ctx.definition.nodes
                    .filterIsInstance<BpmnEndEvent>()
                    .count { it.parentRef == end.parentRef }
                if (peers == 1) {
                    add(
                        RuleDiagnostic(
                            "evt-superfluous-terminate",
                            id,
                            RuleSeverity.ERROR,
                            "terminate end event is superfluous when it is the scope's only end event",
                            end.id,
                        ),
                    )
                }
            }
    }

    private fun eventCardinalityDiagnostics(ctx: BpmnDefinitionContext): List<RuleDiagnostic> = buildList {
        ctx.definition.nodes
            .filterIsInstance<BpmnStartEvent>()
            .filter { !it.isEventSubProcessStart && (ctx.incomingCounts[it.id] ?: 0) > 0 }
            .forEach { start ->
                add(
                    RuleDiagnostic(
                        "evt-start-event-incoming",
                        id,
                        RuleSeverity.ERROR,
                        "start event must not have incoming sequence flow",
                        start.id,
                    ),
                )
            }
        ctx.definition.nodes
            .filterIsInstance<BpmnEndEvent>()
            .filter { (ctx.outgoingCounts[it.id] ?: 0) > 0 }
            .forEach { end ->
                add(
                    RuleDiagnostic(
                        "evt-end-event-outgoing",
                        id,
                        RuleSeverity.ERROR,
                        "end event must not have outgoing sequence flow",
                        end.id,
                    ),
                )
            }
    }
}

@Component
internal class ConditionalFlowRule : BpmnRule {
    override val id = "def-conditional-flows"
    override val metadata = metadata(
        id,
        "Conditional Flows",
        "Allow conditional sequence flows only from decision-capable sources.",
        "Put conditions on flows leaving activities or exclusive, inclusive, and complex gateways, " +
            "never parallel or event-based gateways.",
        listOf("bpmn:SequenceFlow"),
    )
    override fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic> = ctx.definition.sequences
        .filter { !it.conditionExpression.isNullOrBlank() }
        .filter { edge ->
            ctx.nodesById[edge.sourceRef] is BpmnParallelGateway ||
                ctx.nodesById[edge.sourceRef]?.let { node ->
                    node is BpmnGateway &&
                        node !is BpmnComplexGateway &&
                        node !is dev.groknull.bpmner.bpmn.BpmnExclusiveGateway &&
                        node !is dev.groknull.bpmner.bpmn.BpmnInclusiveGateway
                } == true
        }
        .map {
            RuleDiagnostic(
                "flow-invalid-condition",
                id,
                RuleSeverity.ERROR,
                "conditional sequence flow must not leave a parallel or event-based gateway",
                it.id,
            )
        }
}

/** An opt-in alternative retained outside component discovery so inclusive defaults remain active. */
internal class NoInclusiveGatewayRule : BpmnRule {
    override val id = "gtw-no-inclusive-gateway"
    override val metadata = RuleMetadata(
        id = id,
        name = "No Inclusive Gateway",
        slug = "no-inclusive-gateway",
        category = RuleCategory.Gateway,
        intent = "Disallow inclusive gateways when a model profile requires simpler gateway semantics.",
        forModellers = "Use an exclusive or parallel gateway instead of an inclusive gateway.",
        forAI = "Flag every inclusive gateway when this alternative rule is explicitly selected.",
        targetElements = listOf("bpmn:InclusiveGateway"),
        errorMessages = mapOf("default" to "Inclusive gateways are not permitted by this alternative rule"),
        severity = RuleSeverity.ERROR,
        repair = RepairMetadata(kind = RepairKind.LLM_MODEL_PATCH, safety = RepairSafety.LLM_ONLY),
    )
    override fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic> = ctx.definition.nodes
        .filterIsInstance<dev.groknull.bpmner.bpmn.BpmnInclusiveGateway>()
        .map { RuleDiagnostic(id, id, RuleSeverity.ERROR, metadata.errorMessages.getValue("default"), it.id) }
}
