/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring.internal.domain

import dev.groknull.bpmner.authoring.BpmnConformance
import dev.groknull.bpmner.authoring.BpmnContractConformancePort
import dev.groknull.bpmner.authoring.ContractCorrection
import dev.groknull.bpmner.bpmn.BpmnBusinessRuleTask
import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnEdge
import dev.groknull.bpmner.bpmn.BpmnEndEvent
import dev.groknull.bpmner.bpmn.BpmnErrorEventDefinition
import dev.groknull.bpmner.bpmn.BpmnEventBasedGateway
import dev.groknull.bpmner.bpmn.BpmnEventDefinition
import dev.groknull.bpmner.bpmn.BpmnExclusiveGateway
import dev.groknull.bpmner.bpmn.BpmnGateway
import dev.groknull.bpmner.bpmn.BpmnInclusiveGateway
import dev.groknull.bpmner.bpmn.BpmnIntermediateThrowEvent
import dev.groknull.bpmner.bpmn.BpmnManualTask
import dev.groknull.bpmner.bpmn.BpmnMessageEventDefinition
import dev.groknull.bpmner.bpmn.BpmnNode
import dev.groknull.bpmner.bpmn.BpmnNoneEventDefinition
import dev.groknull.bpmner.bpmn.BpmnParallelGateway
import dev.groknull.bpmner.bpmn.BpmnReceiveTask
import dev.groknull.bpmner.bpmn.BpmnScriptTask
import dev.groknull.bpmner.bpmn.BpmnSendTask
import dev.groknull.bpmner.bpmn.BpmnServiceTask
import dev.groknull.bpmner.bpmn.BpmnTask
import dev.groknull.bpmner.bpmn.BpmnTerminateEventDefinition
import dev.groknull.bpmner.bpmn.BpmnUserTask
import dev.groknull.bpmner.bpmn.MultiInstanceLoopCharacteristics
import dev.groknull.bpmner.bpmn.StandardLoopCharacteristics
import dev.groknull.bpmner.bpmn.typeName
import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractDecision
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.ContractGatewayKind
import dev.groknull.bpmner.contract.ContractIntermediateThrow
import dev.groknull.bpmner.contract.ContractIteration
import dev.groknull.bpmner.contract.ContractLoop
import dev.groknull.bpmner.contract.DefaultBranch
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.iteration
import dev.groknull.bpmner.contract.loop
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * R3 (ADR-685-19/-21): deterministically stamps every BPMN attribute the source [ProcessContract]
 * fully determines onto a generated [BpmnDefinition] — the contract's value always wins, so this
 * pass corrects rather than rejects and never fails the stage (ADR-685-22).
 *
 * Seven stamps (ADR-685-21's bucket — `ACTIVITY_TASK_KIND_MISMATCH` is structural, not
 * stamped here: see its exclusion note below):
 * 1. Default-branch edge: `isDefault = true`, condition cleared.
 * 2. Every branch's edge label: `edge.name = branch.label`.
 * 3. Task `multiInstance`, from `activity.modifiers.iteration`.
 * 4. Task `standardLoop`, from `activity.modifiers.loop`.
 * 5. End-event `eventDefinition`, from the `ContractEndState` subtype.
 * 6. Intermediate-throw `eventDefinition`, from the `ContractIntermediateThrow` subtype.
 * 7. Gateway subtype substitution, from `ContractDecision.kind` — a subtype substitution is an
 *    attribute stamp because the four gateway shapes are structurally identical `(id, name,
 *    parentRef)`; substituting a task kind is not (see `ACTIVITY_TASK_KIND_MISMATCH`'s exclusion).
 *
 * Stamping is best-effort per element: a branch/edge match, or an errorRef/messageRef catalogue
 * entry, that cannot be resolved is left for the still-live fidelity check to catch — this pass
 * never invents a catalogue entry or a routing edge (that is structural synthesis, out of scope;
 * ADR-685-21).
 */
@Component
@Suppress("TooManyFunctions") // one focused private stamp per determined attribute
internal class ContractConformancePass : BpmnContractConformancePort {
    private val logger = LoggerFactory.getLogger(ContractConformancePass::class.java)

    override fun conform(
        contract: ProcessContract,
        definition: BpmnDefinition,
    ): BpmnConformance {
        val nodesById = definition.nodes.associateBy { it.id }.toMutableMap()
        val edgesById = definition.sequences.associateBy { it.id }.toMutableMap()
        val outboundBySource = definition.sequences.groupBy { it.sourceRef }
        val corrections = mutableListOf<ContractCorrection>()

        contract.decisions.forEach { decision ->
            stampBranches(decision, outboundBySource[decision.id].orEmpty(), edgesById, corrections)
            stampGatewayKind(decision, nodesById, corrections)
        }
        contract.activities.forEach { activity -> stampActivityModifiers(activity, nodesById, corrections) }
        contract.endStates.forEach { endState -> stampEndState(endState, definition, nodesById, corrections) }
        contract.intermediateThrows.forEach { throwEvent ->
            stampIntermediateThrow(throwEvent, definition, nodesById, corrections)
        }

        if (corrections.isEmpty()) return BpmnConformance(definition, emptyList())

        logger.info("Contract conformance pass applied {} correction(s)", corrections.size)
        val corrected = definition.copy(
            nodes = definition.nodes.map { nodesById.getValue(it.id) },
            sequences = definition.sequences.map { edgesById.getValue(it.id) },
        )
        return BpmnConformance(corrected, corrections.toList())
    }

    // Stamps 1 (default flow) and 2 (edge label), generalised from the one DefaultBranch a
    // decision may carry to every branch: the branch→edge matching itself
    // (nextRef equality, single-outbound fallback when nextRef is null) is unchanged from the
    // predecessor DefaultFlowAssigner.
    private fun stampBranches(
        decision: ContractDecision,
        outbound: List<BpmnEdge>,
        edgesById: MutableMap<String, BpmnEdge>,
        corrections: MutableList<ContractCorrection>,
    ) {
        decision.branches.forEach { branch ->
            val matched = resolveOutboundEdge(branch.nextRef, outbound, decision.branches.size) ?: return@forEach
            var edge = edgesById.getValue(matched.id)

            if (branch is DefaultBranch && (!edge.isDefault || !edge.conditionExpression.isNullOrBlank())) {
                corrections += ContractCorrection(
                    elementId = edge.id,
                    field = "isDefault",
                    modelValue = "isDefault=${edge.isDefault}, condition=${edge.conditionExpression}",
                    contractValue = "isDefault=true",
                )
                edge = edge.copy(isDefault = true, conditionExpression = null)
            }
            if (edge.name != branch.label) {
                corrections += ContractCorrection(
                    elementId = edge.id,
                    field = "name",
                    modelValue = edge.name,
                    contractValue = branch.label,
                )
                edge = edge.copy(name = branch.label)
            }
            edgesById[edge.id] = edge
        }
    }

    private fun resolveOutboundEdge(
        nextRef: String?,
        outbound: List<BpmnEdge>,
        branchCount: Int,
    ): BpmnEdge? = when {
        nextRef != null -> outbound.singleOrNull { it.targetRef == nextRef }
        // Only unambiguous when the decision has exactly one branch: with more than one branch,
        // a second nextRef-less branch would resolve to the same sole edge and overwrite the
        // first branch's stamp (a GATEWAY_BRANCH_COUNT_INSUFFICIENT topology the fidelity check
        // rejects and retries anyway — this pass must not guess which branch owns the edge).
        branchCount == 1 && outbound.size == 1 -> outbound.single()
        else -> null
    }

    // Stamp 7: gateway subtype substitution. Total and lossless only because the four contract
    // gateway kinds share the identical shape (id, name, parentRef) — see the class doc.
    private fun stampGatewayKind(
        decision: ContractDecision,
        nodesById: MutableMap<String, BpmnNode>,
        corrections: MutableList<ContractCorrection>,
    ) {
        val node = nodesById[decision.id] as? BpmnGateway ?: return
        val substitute = decision.kind.substituteGateway(node) ?: return
        corrections += ContractCorrection(
            elementId = decision.id,
            field = "gatewayKind",
            modelValue = node.typeName,
            contractValue = decision.kind.name,
        )
        nodesById[decision.id] = substitute
    }

    private fun ContractGatewayKind.substituteGateway(node: BpmnGateway): BpmnNode? {
        val matches = when (this) {
            ContractGatewayKind.EXCLUSIVE -> node is BpmnExclusiveGateway
            ContractGatewayKind.INCLUSIVE -> node is BpmnInclusiveGateway
            ContractGatewayKind.PARALLEL -> node is BpmnParallelGateway
            ContractGatewayKind.EVENT_BASED -> node is BpmnEventBasedGateway
        }
        if (matches) return null
        return when (this) {
            ContractGatewayKind.EXCLUSIVE -> BpmnExclusiveGateway(node.id, node.name, node.parentRef)
            ContractGatewayKind.INCLUSIVE -> BpmnInclusiveGateway(node.id, node.name, node.parentRef)
            ContractGatewayKind.PARALLEL -> BpmnParallelGateway(node.id, node.name, node.parentRef)
            ContractGatewayKind.EVENT_BASED -> BpmnEventBasedGateway(node.id, node.name, node.parentRef)
        }
    }

    // Stamps 3 (multiInstance) and 4 (standardLoop).
    private fun stampActivityModifiers(
        activity: ContractActivity,
        nodesById: MutableMap<String, BpmnNode>,
        corrections: MutableList<ContractCorrection>,
    ) {
        val task = nodesById[activity.id] as? BpmnTask ?: return
        var updated = task

        val expectedMultiInstance = activity.iteration?.toCharacteristics()
        if (updated.multiInstance != expectedMultiInstance) {
            corrections += ContractCorrection(
                elementId = activity.id,
                field = "multiInstance",
                modelValue = updated.multiInstance?.toString(),
                contractValue = expectedMultiInstance.toString(),
            )
            updated = updated.withMultiInstance(expectedMultiInstance)
        }
        val expectedStandardLoop = activity.loop?.toCharacteristics()
        if (updated.standardLoop != expectedStandardLoop) {
            corrections += ContractCorrection(
                elementId = activity.id,
                field = "standardLoop",
                modelValue = updated.standardLoop?.toString(),
                contractValue = expectedStandardLoop.toString(),
            )
            updated = updated.withStandardLoop(expectedStandardLoop)
        }
        if (updated !== task) nodesById[activity.id] = updated
    }

    private fun ContractIteration.toCharacteristics() = MultiInstanceLoopCharacteristics(
        mode = mode,
        collectionDescription = collectionDescription,
        loopCardinality = loopCardinality,
        completionCondition = completionCondition,
    )

    private fun ContractLoop.toCharacteristics() = StandardLoopCharacteristics(
        testBefore = testBefore,
        loopCondition = loopCondition,
        loopMaximum = loopMaximum,
    )

    private fun BpmnTask.withMultiInstance(multiInstance: MultiInstanceLoopCharacteristics?): BpmnTask = when (this) {
        is BpmnUserTask -> copy(multiInstance = multiInstance)
        is BpmnServiceTask -> copy(multiInstance = multiInstance)
        is BpmnScriptTask -> copy(multiInstance = multiInstance)
        is BpmnBusinessRuleTask -> copy(multiInstance = multiInstance)
        is BpmnSendTask -> copy(multiInstance = multiInstance)
        is BpmnReceiveTask -> copy(multiInstance = multiInstance)
        is BpmnManualTask -> copy(multiInstance = multiInstance)
    }

    private fun BpmnTask.withStandardLoop(standardLoop: StandardLoopCharacteristics?): BpmnTask = when (this) {
        is BpmnUserTask -> copy(standardLoop = standardLoop)
        is BpmnServiceTask -> copy(standardLoop = standardLoop)
        is BpmnScriptTask -> copy(standardLoop = standardLoop)
        is BpmnBusinessRuleTask -> copy(standardLoop = standardLoop)
        is BpmnSendTask -> copy(standardLoop = standardLoop)
        is BpmnReceiveTask -> copy(standardLoop = standardLoop)
        is BpmnManualTask -> copy(standardLoop = standardLoop)
    }

    // Stamp 5. Best-effort: NORMAL/TERMINATE need no catalogue lookup and are always stampable;
    // ERROR/MESSAGE can only be stamped when a matching BpmnErrorRef/BpmnMessageRef already
    // exists in the definition's catalogue — this pass never invents one (ADR-685-21 non-goal).
    private fun stampEndState(
        endState: ContractEndState,
        definition: BpmnDefinition,
        nodesById: MutableMap<String, BpmnNode>,
        corrections: MutableList<ContractCorrection>,
    ) {
        val node = nodesById[endState.id] as? BpmnEndEvent ?: return
        val expected = endState.resolveEventDefinition(definition) ?: return
        if (node.eventDefinition == expected) return
        corrections += ContractCorrection(
            elementId = endState.id,
            field = "eventDefinition",
            modelValue = node.eventDefinition::class.simpleName,
            contractValue = expected::class.simpleName.orEmpty(),
        )
        nodesById[endState.id] = node.copy(eventDefinition = expected)
    }

    private fun ContractEndState.resolveEventDefinition(definition: BpmnDefinition): BpmnEventDefinition? = when (this) {
        is ContractEndState.Normal -> BpmnNoneEventDefinition
        is ContractEndState.Terminate -> BpmnTerminateEventDefinition
        is ContractEndState.Error ->
            definition.errors.firstOrNull { it.code == errorCode }?.let { BpmnErrorEventDefinition(it.id) }
        is ContractEndState.Message ->
            definition.messages.firstOrNull { it.name == messageName }?.let { BpmnMessageEventDefinition(it.id) }
    }

    // Stamp 6, mirroring stamp 5's catalogue-resolution limit.
    private fun stampIntermediateThrow(
        intermediateThrow: ContractIntermediateThrow,
        definition: BpmnDefinition,
        nodesById: MutableMap<String, BpmnNode>,
        corrections: MutableList<ContractCorrection>,
    ) {
        val node = nodesById[intermediateThrow.id] as? BpmnIntermediateThrowEvent ?: return
        val expected = intermediateThrow.resolveEventDefinition(definition) ?: return
        if (node.eventDefinition == expected) return
        corrections += ContractCorrection(
            elementId = intermediateThrow.id,
            field = "eventDefinition",
            modelValue = node.eventDefinition::class.simpleName,
            contractValue = expected::class.simpleName.orEmpty(),
        )
        nodesById[intermediateThrow.id] = node.copy(eventDefinition = expected)
    }

    private fun ContractIntermediateThrow.resolveEventDefinition(definition: BpmnDefinition): BpmnEventDefinition? = when (this) {
        is ContractIntermediateThrow.Message ->
            definition.messages.firstOrNull { it.name == messageName }?.let { BpmnMessageEventDefinition(it.id) }
    }
}
