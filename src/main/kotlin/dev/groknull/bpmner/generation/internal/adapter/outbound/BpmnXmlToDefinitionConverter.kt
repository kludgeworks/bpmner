/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.adapter.outbound

import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnExclusiveGateway
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnServiceTask
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnUserTask
import dev.groknull.bpmner.generation.BpmnXmlParser
import org.camunda.bpm.model.bpmn.Bpmn
import org.camunda.bpm.model.bpmn.BpmnModelInstance
import org.camunda.bpm.model.bpmn.instance.EndEvent
import org.camunda.bpm.model.bpmn.instance.ExclusiveGateway
import org.camunda.bpm.model.bpmn.instance.FlowNode
import org.camunda.bpm.model.bpmn.instance.Process
import org.camunda.bpm.model.bpmn.instance.SequenceFlow
import org.camunda.bpm.model.bpmn.instance.ServiceTask
import org.camunda.bpm.model.bpmn.instance.StartEvent
import org.camunda.bpm.model.bpmn.instance.UserTask
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnDiagram
import org.jmolecules.architecture.hexagonal.SecondaryAdapter
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream

@SecondaryAdapter
@Component
internal open class BpmnXmlToDefinitionConverter : BpmnXmlParser {
    override fun parse(xml: String): BpmnDefinition {
        val model: BpmnModelInstance = Bpmn.readModelFromStream(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        rejectIfHasDiagramInterchange(model)

        val process =
            model.getModelElementsByType(Process::class.java).firstOrNull()
                ?: error("BPMN XML contains no <process> element")

        val nodes = model.getModelElementsByType(FlowNode::class.java).map { it.toBpmnNode() }

        val sequences =
            model.getModelElementsByType(SequenceFlow::class.java).map { flow ->
                BpmnEdge(
                    id = flow.id,
                    sourceRef = flow.source.id,
                    targetRef = flow.target.id,
                    name = flow.name?.takeIf { it.isNotBlank() },
                    conditionExpression = flow.conditionExpression?.textContent?.takeIf { it.isNotBlank() },
                )
            }

        return BpmnDefinition(
            processId = process.id,
            processName = process.name?.takeIf { it.isNotBlank() } ?: process.id,
            nodes = nodes,
            sequences = sequences,
        )
    }

    // Layout is computed downstream by the auto-layout stage; accepting BPMNDI on input
    // would let stale or LLM-supplied coordinates leak through. Reject them at the boundary.
    private fun rejectIfHasDiagramInterchange(model: BpmnModelInstance) {
        if (model.getModelElementsByType(BpmnDiagram::class.java).isNotEmpty()) {
            throw IllegalArgumentException(
                "BPMNDI input rejected — semantic-only XML required. " +
                    "Strip <bpmndi:BPMNDiagram> elements before parsing.",
            )
        }
    }

    private fun FlowNode.toBpmnNode(): BpmnNode {
        val normalisedName = name?.takeIf { it.isNotBlank() }
        return when (this) {
            is StartEvent -> BpmnStartEvent(id = id, name = normalisedName)
            is UserTask -> BpmnUserTask(id = id, name = normalisedName)
            is ServiceTask -> BpmnServiceTask(id = id, name = normalisedName)
            is ExclusiveGateway -> BpmnExclusiveGateway(id = id, name = normalisedName)
            is EndEvent -> BpmnEndEvent(id = id, name = normalisedName)
            else -> error("Unsupported BPMN flow node type for id='$id': ${this::class.simpleName}")
        }
    }
}
