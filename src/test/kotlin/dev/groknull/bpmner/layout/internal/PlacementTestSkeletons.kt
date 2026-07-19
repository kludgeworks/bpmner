/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import dev.groknull.bpmner.layout.internal.BpmnToElkMapper.ElkSkeleton
import org.camunda.bpm.model.bpmn.Bpmn
import org.camunda.bpm.model.bpmn.BpmnModelInstance
import org.eclipse.elk.graph.ElkEdge
import org.eclipse.elk.graph.ElkNode
import org.eclipse.elk.graph.ElkPort
import org.eclipse.elk.graph.util.ElkGraphUtil
import java.io.ByteArrayInputStream

/**
 * Shared skeleton builders for per-processor contract tests.
 *
 * Extracted from [BpmnPlacementPassTest] per rung 2 (reuse, don't duplicate) so
 * [HandlerComponentAlignmentTest], [SubprocessEndStraddleTest], [SubprocessSpineCentringTest],
 * [ExceptionEdgeRoutesTest], and [LoopBackEdgeArcsTest] can all use these helpers.
 */
internal object PlacementTestSkeletons {

    val EVENT_SIZE = BpmnToElkMapper.EVENT_SIZE

    fun parse(xml: String): BpmnModelInstance = Bpmn.readModelFromStream(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

    fun skeleton(
        root: ElkNode,
        nodeMap: Map<String, ElkNode>,
        portMap: Map<String, ElkPort> = emptyMap(),
        edgeMap: Map<String, ElkEdge> = emptyMap(),
        loopBackFlowIds: Set<String> = emptySet(),
    ): ElkSkeleton = ElkSkeleton(root, nodeMap, portMap, edgeMap, loopBackFlowIds)

    @Suppress("LongParameterList")
    fun makeNode(parent: ElkNode, id: String, x: Double, y: Double, w: Double, h: Double): ElkNode {
        val n = ElkGraphUtil.createNode(parent)
        n.identifier = id
        n.x = x
        n.y = y
        n.width = w
        n.height = h
        return n
    }

    fun makePort(host: ElkNode, id: String, x: Double, y: Double): ElkPort {
        val p = ElkGraphUtil.createPort(host)
        p.identifier = id
        p.x = x
        p.y = y
        p.width = BpmnToElkMapper.BOUNDARY_PORT_SIZE
        p.height = BpmnToElkMapper.BOUNDARY_PORT_SIZE
        return p
    }

    @Suppress("LongParameterList")
    fun makeEdge(
        root: ElkNode,
        id: String,
        src: org.eclipse.elk.graph.ElkConnectableShape,
        tgt: ElkNode,
        sx: Double,
        sy: Double,
        ex: Double,
        ey: Double,
    ): ElkEdge {
        val e = ElkGraphUtil.createEdge(root)
        e.identifier = id
        e.sources.add(src)
        e.targets.add(tgt)
        val s = ElkGraphUtil.createEdgeSection(e)
        s.startX = sx
        s.startY = sy
        s.endX = ex
        s.endY = ey
        return e
    }

    @Suppress("LongParameterList")
    fun makeEdgeNoEndpoints(root: ElkNode, id: String, sx: Double, sy: Double, ex: Double, ey: Double): ElkEdge {
        val e = ElkGraphUtil.createEdge(root)
        e.identifier = id
        val s = ElkGraphUtil.createEdgeSection(e)
        s.startX = sx
        s.startY = sy
        s.endX = ex
        s.endY = ey
        return e
    }
}
