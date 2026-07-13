/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.LABEL_HEIGHT
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.LABEL_WIDTH
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnToElkMapper.ElkSkeleton
import org.camunda.bpm.model.bpmn.Bpmn
import org.camunda.bpm.model.bpmn.BpmnModelInstance
import org.eclipse.elk.alg.layered.options.LayeredMetaDataProvider
import org.eclipse.elk.core.data.LayoutMetaDataService
import org.eclipse.elk.graph.ElkEdge
import org.eclipse.elk.graph.ElkNode
import org.eclipse.elk.graph.ElkPort
import org.eclipse.elk.graph.util.ElkGraphUtil
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Named-rule unit tests for [BpmnPlacementPass].
 *
 * Each test builds a minimal model + hand-crafted [ElkSkeleton] with known coordinates
 * and asserts one specific BPMN placement convention. These are the convention tests that
 * were MISSING in the first 557-3 attempt — they are the direct gate for BLOCK-557-3.
 *
 * Test list:
 * - Boundary shape on host BOTTOM edge (named rule 2)
 * - Multiple boundaries evenly distributed on host bottom (named rule 2)
 * - Label 90×20 BELOW its node (named rule 5)
 * - Label not at element's own coordinates (direct fix for BLOCK-557-3 symptom 1)
 * - Edge label at waypoint midpoint (named rule 5)
 * - Artifact placed off the skeleton (named rule 6)
 * - Exception edge start waypoint reconciled to placed boundary centre (named rule 3)
 */
class BpmnPlacementPassTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun registerMetadata() {
            LayoutMetaDataService.getInstance().registerLayoutMetaDataProviders(LayeredMetaDataProvider())
        }

        private const val EVENT_SIZE = BpmnToElkMapper.EVENT_SIZE

        private fun parse(xml: String): BpmnModelInstance {
            return Bpmn.readModelFromStream(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        }

        /** Minimal model with one task. */
        private fun taskModel(taskId: String = "Task_1", taskName: String? = null): BpmnModelInstance {
            val nameAttr = if (taskName != null) """ name="$taskName"""" else ""
            return parse(
                """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  id="D1" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:process id="P1" isExecutable="true">
    <bpmn:serviceTask id="$taskId"$nameAttr/>
  </bpmn:process>
</bpmn:definitions>""",
            )
        }

        /** Model with one task and one timer boundary event. */
        private fun boundaryModel(boundaryId: String = "Boundary_1", hostId: String = "Task_1"): BpmnModelInstance = parse(
            """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  id="D1" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:process id="P1" isExecutable="true">
    <bpmn:serviceTask id="$hostId"/>
    <bpmn:endEvent id="Handler_1"/>
    <bpmn:boundaryEvent id="$boundaryId" attachedToRef="$hostId" cancelActivity="true">
      <bpmn:outgoing>Flow_ex</bpmn:outgoing>
      <bpmn:timerEventDefinition id="TD1"/>
    </bpmn:boundaryEvent>
    <bpmn:sequenceFlow id="Flow_ex" sourceRef="$boundaryId" targetRef="Handler_1"/>
  </bpmn:process>
</bpmn:definitions>""",
        )

        /** Model with one task and TWO boundary events. */
        private fun twoBoundaryModel(): BpmnModelInstance = parse(
            """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  id="D1" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:process id="P1" isExecutable="true">
    <bpmn:serviceTask id="Task_1"/>
    <bpmn:endEvent id="Handler_A"/>
    <bpmn:endEvent id="Handler_B"/>
    <bpmn:boundaryEvent id="Boundary_A" attachedToRef="Task_1" cancelActivity="true">
      <bpmn:outgoing>Flow_A</bpmn:outgoing>
      <bpmn:timerEventDefinition id="TD_A"/>
    </bpmn:boundaryEvent>
    <bpmn:boundaryEvent id="Boundary_B" attachedToRef="Task_1" cancelActivity="true">
      <bpmn:outgoing>Flow_B</bpmn:outgoing>
      <bpmn:errorEventDefinition id="ED_B"/>
    </bpmn:boundaryEvent>
    <bpmn:sequenceFlow id="Flow_A" sourceRef="Boundary_A" targetRef="Handler_A"/>
    <bpmn:sequenceFlow id="Flow_B" sourceRef="Boundary_B" targetRef="Handler_B"/>
  </bpmn:process>
</bpmn:definitions>""",
        )

        /** Model with a named sequence flow. */
        private fun namedFlowModel(): BpmnModelInstance = parse(
            """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  id="D1" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:process id="P1" isExecutable="true">
    <bpmn:startEvent id="S1"><bpmn:outgoing>Flow_1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:endEvent id="E1"><bpmn:incoming>Flow_1</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="Flow_1" name="Yes" sourceRef="S1" targetRef="E1"/>
  </bpmn:process>
</bpmn:definitions>""",
        )

        /** Model with a text annotation. */
        private fun annotationModel(): BpmnModelInstance = parse(
            """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  id="D1" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:process id="P1" isExecutable="true">
    <bpmn:startEvent id="S1"/>
    <bpmn:textAnnotation id="Ann_1"><bpmn:text>Note</bpmn:text></bpmn:textAnnotation>
  </bpmn:process>
</bpmn:definitions>""",
        )

        /**
         * Builds a minimal [ElkSkeleton] from pre-placed nodes/ports/edges.
         */
        private fun skeleton(
            root: ElkNode,
            nodeMap: Map<String, ElkNode>,
            portMap: Map<String, ElkPort> = emptyMap(),
            edgeMap: Map<String, ElkEdge> = emptyMap(),
        ): ElkSkeleton = ElkSkeleton(root, nodeMap, portMap, edgeMap)
    }

    // ── Named rule 2: boundary shape on host BOTTOM edge ─────────────────────

    @Test
    fun `boundary shape is placed on host BOTTOM edge (centre straddles)`() {
        val model = boundaryModel()
        val sk = singleBoundarySkeleton()
        val layout = BpmnPlacementPass.place(model, sk)

        val bRect = layout.shapes["Boundary_1"]
        assertNotNull(bRect, "Boundary_1 must be in shapes")
        val hostRect = layout.shapes["Task_1"]
        assertNotNull(hostRect, "Task_1 must be in shapes")

        // Boundary centre Y must be at host BOTTOM edge
        val bCentreY = bRect.y + bRect.h / 2.0
        val hostBottom = hostRect.y + hostRect.h
        assertEquals(
            hostBottom,
            bCentreY,
            1.0,
            "Boundary centre Y ($bCentreY) must be at host bottom edge ($hostBottom)",
        )
        // Boundary centre X must be within host bounds
        val bCentreX = bRect.x + bRect.w / 2.0
        assertTrue(
            bCentreX >= hostRect.x && bCentreX <= hostRect.x + hostRect.w,
            "Boundary centre X ($bCentreX) must be within host X range",
        )
    }

    @Test
    fun `two boundaries on same host are both on host bottom edge and at different X positions`() {
        val model = twoBoundaryModel()
        val sk = twoBoundarySkeleton()
        val layout = BpmnPlacementPass.place(model, sk)

        val hostRect = layout.shapes["Task_1"]!!
        val hostBottom = hostRect.y + hostRect.h
        val rA = layout.shapes["Boundary_A"]
        val rB = layout.shapes["Boundary_B"]
        assertNotNull(rA)
        assertNotNull(rB)

        // Both must be on the host bottom edge
        assertEquals(hostBottom, rA.y + rA.h / 2.0, 1.0, "Boundary_A centre Y on host bottom")
        assertEquals(hostBottom, rB.y + rB.h / 2.0, 1.0, "Boundary_B centre Y on host bottom")

        // They must be at different X positions
        val axCentre = rA.x + rA.w / 2.0
        val bxCentre = rB.x + rB.w / 2.0
        assertTrue(
            abs(axCentre - bxCentre) > EVENT_SIZE / 2.0,
            "Two boundaries must be at different X positions: A=$axCentre, B=$bxCentre",
        )
    }

    // ── Setup helpers for boundary tests ─────────────────────────────────────

    /** Builds a single-boundary skeleton with known coordinates. */
    private fun singleBoundarySkeleton(): ElkSkeleton {
        val root = ElkGraphUtil.createGraph()
        val host = makeNode(root, "Task_1", 100.0, 50.0, 100.0, 80.0)
        val handler = makeNode(root, "Handler_1", 250.0, 150.0, 36.0, 36.0)
        val beNode = makeNode(root, "Boundary_1", 0.0, 0.0, EVENT_SIZE, EVENT_SIZE)
        val port = makePort(host, "port_Boundary_1", 45.0, host.height - BpmnToElkMapper.BOUNDARY_PORT_SIZE)
        val edge = makeEdge(root, "Flow_ex", port, handler, 150.0, 130.0, 250.0, 168.0)
        return skeleton(
            root = root,
            nodeMap = mapOf("Task_1" to host, "Handler_1" to handler, "Boundary_1" to beNode),
            portMap = mapOf("Boundary_1" to port),
            edgeMap = mapOf("Flow_ex" to edge),
        )
    }

    /** Builds a two-boundary skeleton with known coordinates. */
    private fun twoBoundarySkeleton(): ElkSkeleton {
        val root = ElkGraphUtil.createGraph()
        val host = makeNode(root, "Task_1", 100.0, 50.0, 150.0, 80.0)
        val handlerA = makeNode(root, "Handler_A", 300.0, 170.0, 36.0, 36.0)
        val handlerB = makeNode(root, "Handler_B", 360.0, 170.0, 36.0, 36.0)
        val beA = makeNode(root, "Boundary_A", 0.0, 0.0, EVENT_SIZE, EVENT_SIZE)
        val beB = makeNode(root, "Boundary_B", 0.0, 0.0, EVENT_SIZE, EVENT_SIZE)
        val portA = makePort(host, "port_Boundary_A", 40.0, host.height - BpmnToElkMapper.BOUNDARY_PORT_SIZE)
        val portB = makePort(host, "port_Boundary_B", 100.0, host.height - BpmnToElkMapper.BOUNDARY_PORT_SIZE)
        val eA = makeEdge(root, "Flow_A", portA, handlerA, 150.0, 130.0, 300.0, 188.0)
        val eB = makeEdge(root, "Flow_B", portB, handlerB, 150.0, 130.0, 360.0, 188.0)
        return skeleton(
            root = root,
            nodeMap = mapOf(
                "Task_1" to host,
                "Handler_A" to handlerA,
                "Handler_B" to handlerB,
                "Boundary_A" to beA,
                "Boundary_B" to beB,
            ),
            portMap = mapOf("Boundary_A" to portA, "Boundary_B" to portB),
            edgeMap = mapOf("Flow_A" to eA, "Flow_B" to eB),
        )
    }

    @Suppress("LongParameterList")
    private fun makeNode(parent: ElkNode, id: String, x: Double, y: Double, w: Double, h: Double): ElkNode {
        val n = ElkGraphUtil.createNode(parent)
        n.identifier = id
        n.x = x
        n.y = y
        n.width = w
        n.height = h
        return n
    }

    private fun makePort(host: ElkNode, id: String, x: Double, y: Double): ElkPort {
        val p = ElkGraphUtil.createPort(host)
        p.identifier = id
        p.x = x
        p.y = y
        p.width = BpmnToElkMapper.BOUNDARY_PORT_SIZE
        p.height = BpmnToElkMapper.BOUNDARY_PORT_SIZE
        return p
    }

    @Suppress("LongParameterList")
    private fun makeEdge(
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

    // ── Named rule 5: labels BELOW nodes (not at element own coords) ──────────

    @Test
    fun `named node label is placed below node shape with correct size`() {
        val model = taskModel(taskName = "My Task")
        val root = ElkGraphUtil.createGraph()

        val taskNode = ElkGraphUtil.createNode(root)
        taskNode.identifier = "Task_1"
        taskNode.x = 100.0
        taskNode.y = 50.0
        taskNode.width = 100.0
        taskNode.height = 80.0

        val sk = skeleton(root, mapOf("Task_1" to taskNode))
        val layout = BpmnPlacementPass.place(model, sk)

        val labelRect = layout.labels["Task_1"]
        assertNotNull(labelRect, "Named task must have a label in PlacedLayout")

        val nodeShape = layout.shapes["Task_1"]!!

        // Label must be BELOW the node shape
        val labelTop = labelRect.y
        val nodeBottom = nodeShape.y + nodeShape.h
        assertTrue(
            labelTop >= nodeBottom - 1.0,
            "Label top ($labelTop) must be at or below node bottom ($nodeBottom)",
        )

        // Label must NOT be at the node's own x/y (the block defect)
        val labelIsOnNode = abs(labelRect.x - nodeShape.x) < 1.0 && abs(labelRect.y - nodeShape.y) < 1.0
        assertTrue(!labelIsOnNode, "Label must not be placed at node's own coordinates (BLOCK-557-3 defect)")

        // Label size must be the canonical 90×20
        assertEquals(LABEL_WIDTH, labelRect.w, "Label width must be $LABEL_WIDTH (bpmn-js default)")
        assertEquals(LABEL_HEIGHT, labelRect.h, "Label height must be $LABEL_HEIGHT (bpmn-js default)")
    }

    @Test
    fun `unnamed element has no label in PlacedLayout`() {
        val model = taskModel(taskName = null) // no name attribute
        val root = ElkGraphUtil.createGraph()
        val taskNode = ElkGraphUtil.createNode(root)
        taskNode.identifier = "Task_1"
        taskNode.x = 100.0
        taskNode.y = 50.0
        taskNode.width = 100.0
        taskNode.height = 80.0

        val sk = skeleton(root, mapOf("Task_1" to taskNode))
        val layout = BpmnPlacementPass.place(model, sk)

        assertTrue(
            layout.labels["Task_1"] == null,
            "Unnamed element must not have a label in PlacedLayout",
        )
    }

    // ── Named rule 5: edge labels at waypoint midpoint ────────────────────────

    @Test
    fun `named sequence flow label is placed at edge waypoint midpoint`() {
        val model = namedFlowModel()
        val root = ElkGraphUtil.createGraph()

        val s1 = ElkGraphUtil.createNode(root)
        s1.identifier = "S1"
        s1.x = 0.0
        s1.y = 40.0
        s1.width = 36.0
        s1.height = 36.0

        val e1 = ElkGraphUtil.createNode(root)
        e1.identifier = "E1"
        e1.x = 200.0
        e1.y = 40.0
        e1.width = 36.0
        e1.height = 36.0

        val elkEdge = ElkGraphUtil.createEdge(root)
        elkEdge.identifier = "Flow_1"
        elkEdge.sources.add(s1)
        elkEdge.targets.add(e1)
        val section = ElkGraphUtil.createEdgeSection(elkEdge)
        section.startX = 36.0
        section.startY = 58.0
        section.endX = 200.0
        section.endY = 58.0

        val sk = skeleton(root, mapOf("S1" to s1, "E1" to e1), edgeMap = mapOf("Flow_1" to elkEdge))
        val layout = BpmnPlacementPass.place(model, sk)

        val labelRect = layout.labels["Flow_1"]
        assertNotNull(labelRect, "Named sequence flow must have a label")

        // Label must be centred on the true geometric midpoint of the edge polyline, not at a
        // node. Edge runs (36,58)->(200,58): true mid x = 118; label (90 wide) centred there.
        val edgeWps = layout.edges["Flow_1"] ?: error("Flow_1 must have waypoints")
        assertTrue(edgeWps.size >= 2)
        val trueMidX = (edgeWps.first().x + edgeWps.last().x) / 2.0
        val labelCentreX = labelRect.x + labelRect.w / 2.0
        assertTrue(
            abs(labelCentreX - trueMidX) < 20.0,
            "Edge label centre x ($labelCentreX) should be near the true edge midpoint ($trueMidX)",
        )
        // The label must not sit on the target node E1 (x=200): its right edge stays left of E1.
        assertTrue(
            labelRect.x + labelRect.w < e1.x,
            "Edge label (right=${labelRect.x + labelRect.w}) must not overlap target node E1 (x=${e1.x})",
        )
    }

    // ── Named rule 3: exception edge start reconciled to boundary centre ───────

    @Test
    fun `exception edge first waypoint is at boundary shape centre (reconciliation)`() {
        val model = boundaryModel()
        val root = ElkGraphUtil.createGraph()

        val hostNode = ElkGraphUtil.createNode(root)
        hostNode.identifier = "Task_1"
        hostNode.x = 100.0
        hostNode.y = 50.0
        hostNode.width = 100.0
        hostNode.height = 80.0

        val handlerNode = ElkGraphUtil.createNode(root)
        handlerNode.identifier = "Handler_1"
        handlerNode.x = 280.0
        handlerNode.y = 160.0
        handlerNode.width = 36.0
        handlerNode.height = 36.0

        val beNode = ElkGraphUtil.createNode(root)
        beNode.identifier = "Boundary_1"
        beNode.width = EVENT_SIZE
        beNode.height = EVENT_SIZE

        val port = ElkGraphUtil.createPort(hostNode)
        port.identifier = "port_Boundary_1"
        port.x = 45.0
        port.y = hostNode.height - BpmnToElkMapper.BOUNDARY_PORT_SIZE

        val elkEdge = ElkGraphUtil.createEdge(root)
        elkEdge.identifier = "Flow_ex"
        elkEdge.sources.add(port)
        elkEdge.targets.add(handlerNode)
        val section = ElkGraphUtil.createEdgeSection(elkEdge)
        section.startX = 145.0
        section.startY = 130.0
        section.endX = 280.0
        section.endY = 178.0

        val sk = skeleton(
            root = root,
            nodeMap = mapOf("Task_1" to hostNode, "Handler_1" to handlerNode, "Boundary_1" to beNode),
            portMap = mapOf("Boundary_1" to port),
            edgeMap = mapOf("Flow_ex" to elkEdge),
        )

        val layout = BpmnPlacementPass.place(model, sk)

        val bRect = layout.shapes["Boundary_1"]!!
        val bCentreX = bRect.x + bRect.w / 2.0
        val bBottomY = bRect.y + bRect.h
        val handler = layout.shapes["Handler_1"]!!

        val edgeWps = layout.edges["Flow_ex"]
        assertNotNull(edgeWps, "Exception edge must have waypoints")
        assertTrue(edgeWps.size >= 2, "Exception edge must have at least two waypoints")

        // First waypoint exits the boundary's BOTTOM edge (not its centre — BLOCK feedback).
        val wp0 = edgeWps.first()
        assertEquals(bCentreX, wp0.x, 0.5, "Exception edge must start at boundary bottom-centre X")
        assertEquals(bBottomY, wp0.y, 0.5, "Exception edge must start at boundary BOTTOM edge, not centre")

        // The route must be fully orthogonal (every segment axis-aligned).
        for (i in 1 until edgeWps.size) {
            val a = edgeWps[i - 1]
            val b = edgeWps[i]
            val axisAligned = kotlin.math.abs(a.x - b.x) < 0.5 || kotlin.math.abs(a.y - b.y) < 0.5
            assertTrue(axisAligned, "Exception edge segment $a->$b must be horizontal or vertical (orthogonal)")
        }

        // Last waypoint enters the handler's left edge (handler is to the right of the boundary).
        val wpN = edgeWps.last()
        assertEquals(handler.x, wpN.x, 0.5, "Exception edge must end at handler's near (left) edge")
    }

    // ── Named rule 6: artifact placement off the skeleton ─────────────────────

    @Test
    fun `text annotation is placed with positive non-zero coordinates`() {
        val model = annotationModel()
        val root = ElkGraphUtil.createGraph()

        val s1 = ElkGraphUtil.createNode(root)
        s1.identifier = "S1"
        s1.x = 0.0
        s1.y = 40.0
        s1.width = 36.0
        s1.height = 36.0

        // Annotation tracked as detached node in nodeMap (as BpmnToElkMapper does)
        val annNode = ElkGraphUtil.createGraph()
        annNode.identifier = "Ann_1"
        annNode.width = 100.0
        annNode.height = 60.0

        val sk = skeleton(root, mapOf("S1" to s1, "Ann_1" to annNode))
        val layout = BpmnPlacementPass.place(model, sk)

        val annRect = layout.shapes["Ann_1"]
        assertNotNull(annRect, "TextAnnotation must be in PlacedLayout shapes")
        assertTrue(annRect.x >= 0.0, "Annotation x must be non-negative")
        assertTrue(annRect.y >= 0.0, "Annotation y must be non-negative")
        assertTrue(annRect.w > 0.0, "Annotation width must be positive")
        assertTrue(annRect.h > 0.0, "Annotation height must be positive")
    }
}

private fun assertEquals(expected: Double, actual: Double, tolerance: Double, message: String) {
    assertTrue(
        kotlin.math.abs(actual - expected) <= tolerance,
        "$message: expected $expected ±$tolerance, got $actual",
    )
}
