/*
 * Copyright (c) 2026 The Project Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package dev.groknull.bpmner.generation.internal.adapter.outbound

import dev.groknull.bpmner.core.BpmnBounds
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnNodeNamingPolicy
import dev.groknull.bpmner.core.BpmnWaypoint
import dev.groknull.bpmner.core.NodeType
import org.camunda.bpm.model.bpmn.BpmnModelInstance
import org.camunda.bpm.model.bpmn.instance.Definitions
import org.camunda.bpm.model.bpmn.instance.EndEvent
import org.camunda.bpm.model.bpmn.instance.ExclusiveGateway
import org.camunda.bpm.model.bpmn.instance.FlowNode
import org.camunda.bpm.model.bpmn.instance.Process
import org.camunda.bpm.model.bpmn.instance.ServiceTask
import org.camunda.bpm.model.bpmn.instance.StartEvent
import org.camunda.bpm.model.bpmn.instance.UserTask
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnDiagram
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnPlane
import org.camunda.bpm.model.bpmn.instance.dc.Bounds
import org.camunda.bpm.model.bpmn.instance.di.Waypoint

internal object BpmnModelFactory {
    fun newFlowNode(
        modelInstance: BpmnModelInstance,
        node: BpmnNode,
    ): FlowNode {
        val flowNode =
            when (node.type) {
                NodeType.START_EVENT -> modelInstance.newInstance(StartEvent::class.java)
                NodeType.USER_TASK -> modelInstance.newInstance(UserTask::class.java)
                NodeType.SERVICE_TASK -> modelInstance.newInstance(ServiceTask::class.java)
                NodeType.EXCLUSIVE_GATEWAY -> modelInstance.newInstance(ExclusiveGateway::class.java)
                NodeType.END_EVENT -> modelInstance.newInstance(EndEvent::class.java)
            }
        flowNode.id = node.id
        BpmnNodeNamingPolicy.normalize(node.name)?.let { flowNode.name = it }
        return flowNode
    }

    fun createDiagramPlane(
        modelInstance: BpmnModelInstance,
        definitions: Definitions,
        process: Process,
    ): BpmnPlane {
        val existingDiagram = modelInstance.getModelElementsByType(BpmnDiagram::class.java).firstOrNull()
        val diagram =
            existingDiagram ?: modelInstance.newInstance(BpmnDiagram::class.java).also {
                definitions.addChildElement(it)
            }
        diagram.id = "${process.id}_diagram"

        val existingPlane = diagram.bpmnPlane
        val plane =
            existingPlane ?: modelInstance.newInstance(BpmnPlane::class.java).also {
                diagram.bpmnPlane = it
            }
        plane.id = "${process.id}_plane"
        plane.bpmnElement = process
        return plane
    }

    fun newBounds(
        modelInstance: BpmnModelInstance,
        bounds: BpmnBounds,
    ): Bounds {
        val diBounds = modelInstance.newInstance(Bounds::class.java)
        diBounds.x = bounds.x
        diBounds.y = bounds.y
        diBounds.width = bounds.width
        diBounds.height = bounds.height
        return diBounds
    }

    fun newWaypoint(
        modelInstance: BpmnModelInstance,
        waypoint: BpmnWaypoint,
    ): Waypoint {
        val diWaypoint = modelInstance.newInstance(Waypoint::class.java)
        diWaypoint.x = waypoint.x
        diWaypoint.y = waypoint.y
        return diWaypoint
    }
}
