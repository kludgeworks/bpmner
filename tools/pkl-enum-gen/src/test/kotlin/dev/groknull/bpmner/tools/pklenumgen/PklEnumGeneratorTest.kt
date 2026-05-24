// Copyright 2026 The Project Contributors
// SPDX-License-Identifier: MIT

package dev.groknull.bpmner.tools.pklenumgen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PklEnumGeneratorTest {
    @Test
    fun `node type names use bpmn qname convention in source order`() {
        val nodeTypeNames = PklEnumGenerator.parseNodeTypeNames(FIXTURE_SOURCE)

        assertEquals(
            listOf(
                "bpmn:StartEvent",
                "bpmn:UserTask",
                "bpmn:ServiceTask",
                "bpmn:ScriptTask",
                "bpmn:BusinessRuleTask",
                "bpmn:SendTask",
                "bpmn:ReceiveTask",
                "bpmn:ManualTask",
                "bpmn:ExclusiveGateway",
                "bpmn:ParallelGateway",
                "bpmn:IntermediateCatchEvent",
                "bpmn:IntermediateThrowEvent",
                "bpmn:BoundaryEvent",
                "bpmn:EndEvent",
            ),
            nodeTypeNames,
        )
    }

    @Test
    fun `node properties come from PklProperty annotations`() {
        val nodeProperties = PklEnumGenerator.parseNodeProperties(FIXTURE_SOURCE)

        assertEquals(
            listOf(
                "name",
                "eventDefinition.expression",
                "decisionRef",
                "messageRef",
                "attachedToRef",
                "cancelActivity",
            ),
            nodeProperties,
        )
    }

    private companion object {
        private val FIXTURE_SOURCE =
            """
            sealed interface BpmnNode {
                val id: String
                @get:PklProperty("name")
                val name: String?
            }

            data class BpmnStartEvent(
                override val id: String,
                @PklProperty("eventDefinition.expression")
                val eventDefinition: BpmnEventDefinition,
            ) : BpmnNode

            data class BpmnUserTask(override val id: String) : BpmnNode
            data class BpmnServiceTask(override val id: String) : BpmnNode
            data class BpmnScriptTask(override val id: String) : BpmnNode
            data class BpmnBusinessRuleTask(
                override val id: String,
                @field:PklProperty("decisionRef")
                val decisionRef: String,
            ) : BpmnNode
            data class BpmnSendTask(
                override val id: String,
                @PklProperty
                val messageRef: String,
            ) : BpmnNode
            data class BpmnReceiveTask(override val id: String) : BpmnNode
            data class BpmnManualTask(override val id: String) : BpmnNode
            data class BpmnExclusiveGateway(override val id: String) : BpmnNode
            data class BpmnParallelGateway(override val id: String) : BpmnNode
            data class BpmnIntermediateCatchEvent(override val id: String) : BpmnNode
            data class BpmnIntermediateThrowEvent(override val id: String) : BpmnNode
            data class BpmnBoundaryEvent(
                override val id: String,
                @get:PklProperty("attachedToRef") val attachedToRef: String,
                @get:PklProperty("cancelActivity")
                val cancelActivity: Boolean,
            ) : BpmnNode
            data class BpmnEndEvent(override val id: String) : BpmnNode

            data class BpmnEdge(val id: String)
            """.trimIndent()
    }
}
