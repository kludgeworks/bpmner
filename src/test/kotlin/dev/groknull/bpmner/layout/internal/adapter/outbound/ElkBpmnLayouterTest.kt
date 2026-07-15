/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.xmlunit.assertj.XmlAssert
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Layer 4: end-to-end pipeline tests through [ElkBpmnLayouter].
 *
 * Verifies semantic DI coverage, positive geometry, routed edges, containment,
 * boundary attachment, spatial invariants, and deterministic output.
 * Never compares exact coordinates or asserts parity with the JavaScript layout engine.
 */
@Suppress("TooManyFunctions", "LargeClass")
class ElkBpmnLayouterTest {

    private val layouter = ElkBpmnLayouter()

    // ── Flat corpus — invariants (existing 4 fixtures) ────────────────────────

    @ParameterizedTest(name = "flat corpus invariants: {0}")
    @ValueSource(
        strings = [
            "representative-process.bpmn",
            "explicit-cycle.bpmn",
            "annotation-and-group.bpmn",
            "long-labels.bpmn",
        ],
    )
    fun `flat corpus fixture satisfies all DI invariants`(fixture: String) {
        val inputXml = loadCorpus(fixture)
        val result = layouter.layout(inputXml)
        val (expectedShapeIds, expectedEdgeIds) = semanticIds(inputXml)
        assertExactlyOneDiagram(result)
        val asserter = assertXml(result)
        for (id in expectedShapeIds) asserter.nodesByXPath("//bpmndi:BPMNShape[@bpmnElement='$id']").exist()
        for (id in expectedEdgeIds) asserter.nodesByXPath("//bpmndi:BPMNEdge[@bpmnElement='$id']").exist()
        assertPositiveBounds(result)
        assertMinWaypoints(result, minCount = 2)
    }

    // ── Subprocess corpus — invariants ────────────────────────────────────────

    @ParameterizedTest(name = "subprocess corpus invariants: {0}")
    @ValueSource(
        strings = [
            "subprocess-flat.bpmn",
            "subprocess-nested.bpmn",
            "subprocess-branch.bpmn",
            "subprocess-loop.bpmn",
        ],
    )
    fun `subprocess corpus fixture satisfies all DI invariants`(fixture: String) {
        val inputXml = loadCorpus(fixture)
        val result = layouter.layout(inputXml)
        val (expectedShapeIds, expectedEdgeIds) = semanticIds(inputXml)
        assertExactlyOneDiagram(result)
        val asserter = assertXml(result)
        for (id in expectedShapeIds) asserter.nodesByXPath("//bpmndi:BPMNShape[@bpmnElement='$id']").exist()
        for (id in expectedEdgeIds) asserter.nodesByXPath("//bpmndi:BPMNEdge[@bpmnElement='$id']").exist()
        assertPositiveBounds(result)
        assertMinWaypoints(result, minCount = 2)
    }

    // ── Boundary event corpus — invariants ────────────────────────────────────

    @ParameterizedTest(name = "boundary corpus invariants: {0}")
    @ValueSource(
        strings = [
            "boundary-timer-task.bpmn",
            "boundary-error-task.bpmn",
            "boundary-multi.bpmn",
            "boundary-on-subprocess.bpmn",
        ],
    )
    fun `boundary corpus fixture satisfies all DI invariants`(fixture: String) {
        val inputXml = loadCorpus(fixture)
        val result = layouter.layout(inputXml)
        val (expectedShapeIds, expectedEdgeIds) = semanticIds(inputXml)
        assertExactlyOneDiagram(result)
        val asserter = assertXml(result)
        for (id in expectedShapeIds) asserter.nodesByXPath("//bpmndi:BPMNShape[@bpmnElement='$id']").exist()
        for (id in expectedEdgeIds) asserter.nodesByXPath("//bpmndi:BPMNEdge[@bpmnElement='$id']").exist()
        assertPositiveBounds(result)
        assertMinWaypoints(result, minCount = 2)
    }

    // ── Spatial invariants: subprocess containment ────────────────────────────

    @Test
    fun `subprocess child shapes are contained within their parent subprocess shape bounds`() {
        val result = layouter.layout(loadCorpus("subprocess-flat.bpmn"))
        // Non-terminating children are fully contained; the terminating end event straddles
        // the right border (asserted separately below).
        assertChildrenContainedInParent(result, "SubProcess_1", listOf("SubStart_1", "SubTask_1"))
    }

    @Test
    fun `subprocess-terminating end event straddles the container right border`() {
        // straddleSubprocessEnds (phase-2 named rule): end events in a subprocess are placed
        // so their centre sits on the subprocess right border (half inside, half outside).
        val result = layouter.layout(loadCorpus("subprocess-flat.bpmn"))
        assertStraddlesRightBorder(result, "SubEnd_1", "SubProcess_1")
    }

    private fun assertStraddlesRightBorder(xml: String, endId: String, containerId: String) {
        val doc = parseXmlDoc(xml)
        val end = shapeBounds(doc, endId)
        val container = shapeBounds(doc, containerId)
        val endCx = end["x"]!! + end["width"]!! / 2.0
        val rightBorder = container["x"]!! + container["width"]!!
        val endRight = end["x"]!! + end["width"]!!
        // Centre must be near the right border, and the shape must overlap (straddle) it
        assertTrue(
            kotlin.math.abs(endCx - rightBorder) <= BpmnToElkMapper.EVENT_SIZE / 2.0 + 1.0,
            "End '$endId' centre ($endCx) must be near container '$containerId' right border ($rightBorder)",
        )
        // Right half must be outside (endRight > rightBorder)
        assertTrue(endRight > rightBorder - 1.0, "End '$endId' right ($endRight) must be at or past the border ($rightBorder)")
    }

    @Test
    fun `two-level nested subprocess grandchild is inside inner which is inside outer`() {
        val result = layouter.layout(loadCorpus("subprocess-nested.bpmn"))
        assertChildrenContainedInParent(result, "SubProcess_outer", listOf("SubProcess_inner"))
        assertChildrenContainedInParent(result, "SubProcess_inner", listOf("GrandchildTask"))
    }

    @Test
    fun `subprocess BPMNShapes have isExpanded true`() {
        val result = layouter.layout(loadCorpus("subprocess-flat.bpmn"))
        assertXml(result).nodesByXPath("//bpmndi:BPMNShape[@bpmnElement='SubProcess_1' and @isExpanded='true']").exist()
    }

    @Test
    fun `subprocess with internal branch children contained in subprocess`() {
        val result = layouter.layout(loadCorpus("subprocess-branch.bpmn"))
        // SubEnd straddles the right border (asserted separately); the rest are fully contained.
        // SubEnd straddles the right border; the other children are fully inside.
        assertChildrenContainedInParent(
            result,
            "SubProcess_1",
            listOf("SubStart", "Gw_split", "Task_upper", "Task_lower", "Gw_join"),
        )
        assertStraddlesRightBorder(result, "SubEnd", "SubProcess_1")
    }

    // ── Edge connectivity: waypoints actually touch their source/target shapes ─
    // These catch the double-offset bug where intra-subprocess / cross-hierarchy edge
    // waypoints are reported relative to the compound node but shapes are absolute.

    @ParameterizedTest(name = "edges connect endpoints: {0}")
    @ValueSource(
        strings = [
            "subprocess-flat.bpmn",
            "subprocess-nested.bpmn",
            "subprocess-branch.bpmn",
            "subprocess-loop.bpmn",
            "boundary-timer-task.bpmn",
            "boundary-error-task.bpmn",
            "boundary-multi.bpmn",
            "boundary-on-subprocess.bpmn",
        ],
    )
    fun `every sequence flow first and last waypoint touch its source and target shapes`(fixture: String) {
        val result = layouter.layout(loadCorpus(fixture))
        assertEdgesConnectEndpoints(result, loadCorpus(fixture))
    }

    // ── Spatial invariants: boundary event attachment ─────────────────────────

    @Test
    fun `timer boundary event centre lies on host task BOTTOM edge`() {
        val result = layouter.layout(loadCorpus("boundary-timer-task.bpmn"))
        assertBoundaryOnHostBottomEdge(result, "Boundary_timer", "Task_process")
    }

    @Test
    fun `error boundary event centre lies on host task BOTTOM edge`() {
        val result = layouter.layout(loadCorpus("boundary-error-task.bpmn"))
        assertBoundaryOnHostBottomEdge(result, "Boundary_error", "Task_process")
    }

    @Test
    fun `two boundary events on same host both lie on host BOTTOM edge`() {
        val result = layouter.layout(loadCorpus("boundary-multi.bpmn"))
        assertBoundaryOnHostBottomEdge(result, "Boundary_timer", "Task_process")
        assertBoundaryOnHostBottomEdge(result, "Boundary_error", "Task_process")
    }

    @Test
    fun `boundary event on subprocess lies on subprocess BOTTOM edge`() {
        val result = layouter.layout(loadCorpus("boundary-on-subprocess.bpmn"))
        assertBoundaryOnHostBottomEdge(result, "Boundary_timer", "SubProcess_1")
    }

    @Test
    fun `exception flow first waypoint is near boundary event centre`() {
        val result = layouter.layout(loadCorpus("boundary-timer-task.bpmn"))
        assertExceptionEdgeNearBoundary(result, "Flow_timeout", "Boundary_timer")
    }

    // ── Edge endpoints land on the target's border, not its centre ────────────

    @Test
    fun `exception-lane edges end on the target event's near border not its centre`() {
        val result = layouter.layout(loadCorpus("boundary-multi.bpmn"))
        val doc = parseXmlDoc(result)
        // Handle Error -> Failed (End_error) and Cancel Order -> Cancelled (End_timeout).
        assertEdgeEndsOnBorder(doc, "Flow_4", "End_error")
        assertEdgeEndsOnBorder(doc, "Flow_3", "End_timeout")
    }

    @Test
    fun `timer exception edge ends on the target event's border`() {
        val result = layouter.layout(loadCorpus("boundary-timer-task.bpmn"))
        val doc = parseXmlDoc(result)
        assertEdgeEndsOnBorder(doc, "Flow_3", "End_timeout")
    }

    /** The last waypoint of [edgeId] must lie on the border of [targetId], not inside it. */
    private fun assertEdgeEndsOnBorder(doc: org.w3c.dom.Document, edgeId: String, targetId: String) {
        val t = shapeBounds(doc, targetId)
        val end = edgeWaypoints(doc, edgeId).last()
        val left = t["x"]!!
        val right = left + t["width"]!!
        val top = t["y"]!!
        val bottom = top + t["height"]!!
        val cx = left + t["width"]!! / 2.0
        val cy = top + t["height"]!! / 2.0
        // On the border = within tolerance of an edge line AND not near the centre.
        val onVertical = (kotlin.math.abs(end.first - left) < 2.0 || kotlin.math.abs(end.first - right) < 2.0) &&
            end.second in (top - 2.0)..(bottom + 2.0)
        val onHorizontal = (kotlin.math.abs(end.second - top) < 2.0 || kotlin.math.abs(end.second - bottom) < 2.0) &&
            end.first in (left - 2.0)..(right + 2.0)
        val distToCentre = Math.hypot(end.first - cx, end.second - cy)
        assertTrue(
            (onVertical || onHorizontal) && distToCentre > 5.0,
            "Edge '$edgeId' end $end must be on the border of '$targetId' [$left,$top,$right,$bottom], not its centre",
        )
    }

    @Test
    fun `subprocess exit flow starts from the straddling end event's right edge`() {
        val result = layouter.layout(loadCorpus("subprocess-flat.bpmn"))
        val doc = parseXmlDoc(result)
        val done = shapeBounds(doc, "SubEnd_1")
        val start = edgeWaypoints(doc, "Flow_from_sub").first()
        val doneRight = done["x"]!! + done["width"]!!
        val doneCy = done["y"]!! + done["height"]!! / 2.0
        assertTrue(
            kotlin.math.abs(start.first - doneRight) < 5.0 && kotlin.math.abs(start.second - doneCy) < 5.0,
            "Flow_from_sub must start at Done's right edge ($doneRight,$doneCy), was $start",
        )
    }

    @Test
    fun `main-flow edge between two co-row nodes with a clear corridor is a straight line`() {
        // Task_process -> Task_confirm sit on the same row with nothing between them (the error
        // handler is on its own lane below). The edge must be a straight 2-point horizontal line.
        val result = layouter.layout(loadCorpus("boundary-error-task.bpmn"))
        val doc = parseXmlDoc(result)
        val wps = edgeWaypoints(doc, "Flow_normal")
        assertEquals(2, wps.size, "Flow_normal must be a straight line (2 waypoints), was $wps")
        assertTrue(
            kotlin.math.abs(wps.first().second - wps.last().second) < 1.0,
            "Flow_normal must be horizontal (equal y at both ends), was $wps",
        )
    }

    // ── Exception-lane invariants (handler not on primary baseline) ──

    @Test
    fun `error handler is in a separate vertical band from the main flow`() {
        // Handler is placed in its own row — the bands must not overlap.
        val result = layouter.layout(loadCorpus("boundary-error-task.bpmn"))
        val doc = parseXmlDoc(result)
        val mainNodes = listOf("Task_process", "Task_confirm", "End_1")
            .map { shapeBounds(doc, it) }
        val mainTop = mainNodes.minOf { it["y"]!! }
        val mainBottom = mainNodes.maxOf { it["y"]!! + it["height"]!! }
        val handler = shapeBounds(doc, "Task_handle")
        val handlerTop = handler["y"]!!
        val handlerBottom = handlerTop + handler["height"]!!
        val separated = handlerBottom <= mainTop || handlerTop >= mainBottom
        assertTrue(
            separated,
            "Handler band [$handlerTop,$handlerBottom] and main flow band [$mainTop,$mainBottom] must not overlap",
        )
    }

    @Test
    fun `timer exception handler is in a separate vertical band from the main flow`() {
        // Handler nodes are placed in a separate row from the main flow — the rows must not overlap vertically.
        val result = layouter.layout(loadCorpus("boundary-timer-task.bpmn"))
        val doc = parseXmlDoc(result)
        // Main flow nodes
        val mainNodes = listOf("Start_1", "Task_process", "Task_ship", "End_ok")
            .map { shapeBounds(doc, it) }
        val mainTop = mainNodes.minOf { it["y"]!! }
        val mainBottom = mainNodes.maxOf { it["y"]!! + it["height"]!! }
        // Handler flow nodes
        val handlerNodes = listOf("Task_cancel", "End_timeout")
            .map { shapeBounds(doc, it) }
        val handlerTop = handlerNodes.minOf { it["y"]!! }
        val handlerBottom = handlerNodes.maxOf { it["y"]!! + it["height"]!! }
        // The two bands must not overlap — one must be entirely above or below the other.
        val separated = handlerBottom <= mainTop || handlerTop >= mainBottom
        assertTrue(
            separated,
            "Handler band [$handlerTop,$handlerBottom] and main flow band [$mainTop,$mainBottom] must not overlap",
        )
    }

    @Test
    fun `exception edge does not pass through its host node`() {
        val result = layouter.layout(loadCorpus("boundary-error-task.bpmn"))
        val doc = parseXmlDoc(result)
        val host = shapeBounds(doc, "Task_process")
        val wps = edgeWaypoints(doc, "Flow_error")
        // No waypoint may lie strictly inside the host box (edge must route around/below it).
        val hostL = host["x"]!!
        val hostR = hostL + host["width"]!!
        val hostT = host["y"]!!
        val hostB = hostT + host["height"]!!
        for ((x, y) in wps) {
            val inside = x > hostL + 1 && x < hostR - 1 && y > hostT + 1 && y < hostB - 1
            assertTrue(!inside, "Flow_error waypoint ($x,$y) must not be inside host box [$hostL,$hostT,$hostR,$hostB]")
        }
    }

    // ── Label invariants: labels below nodes ──

    @Test
    fun `named node labels are placed below their node shape (not on top of it)`() {
        val result = layouter.layout(loadCorpus("boundary-error-task.bpmn"))
        assertLabelsBelow(result)
    }

    @Test
    fun `subprocess with inner loop satisfies all DI invariants`() {
        val result = layouter.layout(loadCorpus("subprocess-loop.bpmn"))
        val inputXml = loadCorpus("subprocess-loop.bpmn")
        val (expectedShapeIds, expectedEdgeIds) = semanticIds(inputXml)
        assertExactlyOneDiagram(result)
        val asserter = assertXml(result)
        for (id in expectedShapeIds) asserter.nodesByXPath("//bpmndi:BPMNShape[@bpmnElement='$id']").exist()
        for (id in expectedEdgeIds) asserter.nodesByXPath("//bpmndi:BPMNEdge[@bpmnElement='$id']").exist()
        assertPositiveBounds(result)
        assertMinWaypoints(result, minCount = 2)
    }

    // ── Determinism (parametric, all corpus fixtures) ─────────────────────────

    @ParameterizedTest(name = "determinism: {0}")
    @ValueSource(
        strings = [
            "representative-process.bpmn",
            "subprocess-flat.bpmn",
            "subprocess-nested.bpmn",
            "boundary-timer-task.bpmn",
            "boundary-multi.bpmn",
        ],
    )
    fun `repeated layout of same input produces stable DI geometry`(fixture: String) {
        val xml = loadCorpus(fixture)
        val firstDi = diCoordinates(layouter.layout(xml))
        val secondDi = diCoordinates(layouter.layout(xml))
        assertEquals(firstDi, secondDi, "ELK layout geometry was not deterministic for $fixture")
    }

    // ── Existing focused behavioral tests ─────────────────────────────────────

    @Test
    fun `existing DI is replaced not duplicated`() {
        val xmlWithDi = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                  id="Definitions_1" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:process id="Process_1" isExecutable="true">
    <bpmn:startEvent id="Start_1"><bpmn:outgoing>Flow_1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:endEvent id="End_1"><bpmn:incoming>Flow_1</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="Flow_1" sourceRef="Start_1" targetRef="End_1"/>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="OldDiagram">
    <bpmndi:BPMNPlane id="OldPlane" bpmnElement="Process_1">
      <bpmndi:BPMNShape id="OldShape" bpmnElement="Start_1">
        <dc:Bounds x="0" y="0" width="36" height="36"/>
      </bpmndi:BPMNShape>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>"""
        val result = layouter.layout(xmlWithDi)
        assertExactlyOneDiagram(result)
        assertXml(result).nodesByXPath("//bpmndi:BPMNShape[@bpmnElement='Start_1']").exist()
        assertXml(result).nodesByXPath("//bpmndi:BPMNEdge[@bpmnElement='Flow_1']").exist()
    }

    @Test
    fun `group box encloses the whole process flow`() {
        val result = layouter.layout(loadCorpus("annotation-and-group.bpmn"))
        val doc = parseXmlDoc(result)
        // A BPMN group is a visual box drawn AROUND a region — it must enclose the flow nodes,
        // not float empty in a corner. Every flow-node shape must lie inside the group box.
        val group = shapeBounds(doc, "Group_1")
        for (id in listOf("Start_1", "Task_1", "Task_2", "End_1")) {
            val node = shapeBounds(doc, id)
            assertTrue(
                node["x"]!! >= group["x"]!! &&
                    node["y"]!! >= group["y"]!! &&
                    node["x"]!! + node["width"]!! <= group["x"]!! + group["width"]!! &&
                    node["y"]!! + node["height"]!! <= group["y"]!! + group["height"]!!,
                "Flow node '$id' ($node) must be enclosed by the group box ($group)",
            )
        }
    }

    @Test
    fun `text annotation is placed near its associated task`() {
        val result = layouter.layout(loadCorpus("annotation-and-group.bpmn"))
        val doc = parseXmlDoc(result)
        // Anno_1 is associated with Task_1; its shape should sit near Task_1 (short connector),
        // not dumped in a far corner.
        val anno = shapeBounds(doc, "Anno_1")
        val task = shapeBounds(doc, "Task_1")
        val annoCx = anno["x"]!! + anno["width"]!! / 2.0
        val annoCy = anno["y"]!! + anno["height"]!! / 2.0
        val taskCx = task["x"]!! + task["width"]!! / 2.0
        val taskCy = task["y"]!! + task["height"]!! / 2.0
        val dist = Math.hypot(annoCx - taskCx, annoCy - taskCy)
        assertTrue(dist < 250.0, "Annotation must be near its associated task Task_1; dist=$dist")
    }

    @Test
    fun `explicit cycle produces routed feedback edge`() {
        val result = layouter.layout(loadCorpus("explicit-cycle.bpmn"))
        assertXml(result).nodesByXPath("//bpmndi:BPMNEdge[@bpmnElement='Flow_retry']").exist()
    }

    @Test
    fun `malformed XML throws BpmnAutoLayoutException`() {
        kotlin.test.assertFailsWith<dev.groknull.bpmner.layout.BpmnAutoLayoutException> {
            layouter.layout("not xml")
        }
    }

    // ── Spatial assertion helpers ─────────────────────────────────────────────

    /**
     * Asserts every child ID's shape bounds are fully contained within the parent shape bounds.
     * Applies a 1px tolerance for rounding artefacts.
     */
    private fun assertChildrenContainedInParent(xml: String, parentId: String, childIds: List<String>) {
        val doc = parseXmlDoc(xml)
        val parentBounds = shapeBounds(doc, parentId)
        for (childId in childIds) {
            val childBounds = shapeBounds(doc, childId)
            val tolerance = 1.0
            assertTrue(
                childBounds["x"]!! >= parentBounds["x"]!! - tolerance,
                "$childId left (${childBounds["x"]}) must be inside $parentId left (${parentBounds["x"]})",
            )
            assertTrue(
                childBounds["y"]!! >= parentBounds["y"]!! - tolerance,
                "$childId top (${childBounds["y"]}) must be inside $parentId top (${parentBounds["y"]})",
            )
            assertTrue(
                childBounds["x"]!! + childBounds["width"]!! <= parentBounds["x"]!! + parentBounds["width"]!! + tolerance,
                "$childId right must be inside $parentId right",
            )
            assertTrue(
                childBounds["y"]!! + childBounds["height"]!! <= parentBounds["y"]!! + parentBounds["height"]!! + tolerance,
                "$childId bottom must be inside $parentId bottom",
            )
        }
    }

    /**
     * Asserts the boundary event centre is within EVENT_SIZE/2 of the host's BOTTOM edge.
     * Per AD-557-10: all retained boundary events (timer/error) exit the host's bottom edge.
     */
    private fun assertBoundaryOnHostBottomEdge(xml: String, boundaryId: String, hostId: String) {
        val doc = parseXmlDoc(xml)
        val hostBounds = shapeBounds(doc, hostId)
        val beBounds = shapeBounds(doc, boundaryId)
        val tolerance = EVENT_SIZE / 2.0
        val beCy = beBounds["y"]!! + beBounds["height"]!! / 2.0
        val hostBottom = hostBounds["y"]!! + hostBounds["height"]!!

        assertTrue(
            Math.abs(beCy - hostBottom) <= tolerance,
            "Boundary '$boundaryId' centre Y ($beCy) must be near host '$hostId' BOTTOM edge ($hostBottom) ±$tolerance",
        )
    }

    /**
     * Asserts every named shape's BPMNLabel (if present) has its top-left Y at or below the
     * shape's own top-left Y. This is the direct regression guard for BLOCK-557-3 symptom 1
     * (label placed at the same coordinates as its node, visually on top of it).
     */
    private fun assertLabelsBelow(xml: String) {
        val doc = parseXmlDoc(xml)
        val diNs = "http://www.omg.org/spec/BPMN/20100524/DI"
        val dcNs = "http://www.omg.org/spec/DD/20100524/DC"
        val shapes = doc.getElementsByTagNameNS(diNs, "BPMNShape")
        for (i in 0 until shapes.length) {
            assertShapeLabelBelow(shapes.item(i) as org.w3c.dom.Element, diNs, dcNs)
        }
    }

    @Suppress("ReturnCount")
    private fun assertShapeLabelBelow(shape: org.w3c.dom.Element, diNs: String, dcNs: String) {
        val shapeId = shape.getAttribute("bpmnElement")
        val shapeBoundsNodes = shape.getElementsByTagNameNS(dcNs, "Bounds")
        if (shapeBoundsNodes.length == 0) return
        val shapeB = shapeBoundsNodes.item(0) as org.w3c.dom.Element
        val shapeY = shapeB.getAttribute("y").toDoubleOrNull() ?: return
        val labels = shape.getElementsByTagNameNS(diNs, "BPMNLabel")
        if (labels.length == 0) return
        val label = labels.item(0) as org.w3c.dom.Element
        val lbNodes = label.getElementsByTagNameNS(dcNs, "Bounds")
        if (lbNodes.length == 0) return
        val lb = lbNodes.item(0) as org.w3c.dom.Element
        val labelY = lb.getAttribute("y").toDoubleOrNull() ?: return
        // Core check: label must not be placed AT the node's own top (the BLOCK-557-3 defect)
        assertTrue(
            labelY >= shapeY,
            "Label for '$shapeId': y=$labelY must not coincide with node top=$shapeY (label on node)",
        )
    }

    /**
     * Asserts the boundary event centre is within EVENT_SIZE/2 of at least one edge of the host.
     * Kept for any tests that use the less-strict perimeter check.
     */
    @Suppress("unused")
    private fun assertBoundaryOnHostPerimeter(xml: String, boundaryId: String, hostId: String) {
        val doc = parseXmlDoc(xml)
        val hostBounds = shapeBounds(doc, hostId)
        val beBounds = shapeBounds(doc, boundaryId)
        val tolerance = EVENT_SIZE
        val beCx = beBounds["x"]!! + beBounds["width"]!! / 2.0
        val beCy = beBounds["y"]!! + beBounds["height"]!! / 2.0
        val hostLeft = hostBounds["x"]!!
        val hostRight = hostLeft + hostBounds["width"]!!
        val hostTop = hostBounds["y"]!!
        val hostBottom = hostTop + hostBounds["height"]!!

        val nearLeft = Math.abs(beCx - hostLeft) <= tolerance
        val nearRight = Math.abs(beCx - hostRight) <= tolerance
        val nearTop = Math.abs(beCy - hostTop) <= tolerance
        val nearBottom = Math.abs(beCy - hostBottom) <= tolerance

        assertTrue(
            nearLeft || nearRight || nearTop || nearBottom,
            "Boundary '$boundaryId' centre ($beCx,$beCy) must be within $tolerance of a host '$hostId' edge " +
                "(left=$hostLeft, right=$hostRight, top=$hostTop, bottom=$hostBottom)",
        )
    }

    /**
     * Asserts the first waypoint of the exception edge is within EVENT_SIZE of the boundary event centre.
     */
    private fun assertExceptionEdgeNearBoundary(xml: String, edgeId: String, boundaryId: String) {
        val doc = parseXmlDoc(xml)
        val beBounds = shapeBounds(doc, boundaryId)
        val beCx = beBounds["x"]!! + beBounds["width"]!! / 2.0
        val beCy = beBounds["y"]!! + beBounds["height"]!! / 2.0

        val edges = doc.getElementsByTagNameNS("http://www.omg.org/spec/BPMN/20100524/DI", "BPMNEdge")
        val edge = (0 until edges.length)
            .map { edges.item(it) as org.w3c.dom.Element }
            .firstOrNull { it.getAttribute("bpmnElement") == edgeId }
            ?: error("Edge $edgeId not found in DI")

        val waypoints = edge.getElementsByTagNameNS("http://www.omg.org/spec/DD/20100524/DI", "waypoint")
        assertTrue(waypoints.length >= 1, "Edge $edgeId must have at least one waypoint")
        val wp = waypoints.item(0) as org.w3c.dom.Element
        val wpx = wp.getAttribute("x").toDouble()
        val wpy = wp.getAttribute("y").toDouble()
        val dist = Math.sqrt((wpx - beCx) * (wpx - beCx) + (wpy - beCy) * (wpy - beCy))
        assertTrue(
            dist <= EVENT_SIZE * 2,
            "Edge '$edgeId' first waypoint must be near boundary '$boundaryId' centre; dist=$dist",
        )
    }

    /**
     * For every sequence flow, asserts its first waypoint lies on/near the source shape and
     * its last waypoint on/near the target shape. Catches detached edges (the double-offset
     * bug for intra-subprocess and cross-hierarchy edges).
     */
    private fun assertEdgesConnectEndpoints(resultXml: String, inputXml: String) {
        val resultDoc = parseXmlDoc(resultXml)
        val inputDoc = parseXmlDoc(inputXml)
        val bpmnNs = "http://www.omg.org/spec/BPMN/20100524/MODEL"
        val flows = inputDoc.getElementsByTagNameNS(bpmnNs, "sequenceFlow")
        for (i in 0 until flows.length) {
            val flow = flows.item(i) as org.w3c.dom.Element
            val flowId = flow.getAttribute("id")
            val srcId = flow.getAttribute("sourceRef")
            val tgtId = flow.getAttribute("targetRef")
            val wps = edgeWaypoints(resultDoc, flowId)
            assertTrue(wps.size >= 2, "Flow '$flowId' must have >= 2 waypoints")
            assertWaypointNearShape(resultDoc, wps.first(), srcId, flowId, "source")
            assertWaypointNearShape(resultDoc, wps.last(), tgtId, flowId, "target")
        }
    }

    private fun edgeWaypoints(doc: org.w3c.dom.Document, edgeId: String) = LayoutDiInspector.edgeWaypoints(doc, edgeId)

    private fun assertWaypointNearShape(
        doc: org.w3c.dom.Document,
        wp: Pair<Double, Double>,
        shapeId: String,
        flowId: String,
        role: String,
    ) {
        val b = shapeBounds(doc, shapeId)
        val left = b["x"]!!
        val top = b["y"]!!
        val right = left + b["width"]!!
        val bottom = top + b["height"]!!
        // Waypoint must lie within the shape's bounds expanded by a tolerance (edges attach to
        // the border, not the centre; a detached edge lands hundreds of px away).
        val tol = 40.0
        val (wx, wy) = wp
        val inside = wx >= left - tol && wx <= right + tol && wy >= top - tol && wy <= bottom + tol
        assertTrue(
            inside,
            "Flow '$flowId' $role waypoint ($wx,$wy) is not near shape '$shapeId' " +
                "bounds [$left,$top,$right,$bottom] (tol $tol) — edge is detached",
        )
    }

    private fun shapeBounds(doc: org.w3c.dom.Document, bpmnElementId: String) = LayoutDiInspector.shapeBounds(doc, bpmnElementId)

    // ── Existing helpers ──────────────────────────────────────────────────────

    /**
     * Derives the shape and edge IDs that must appear in the DI output by reading
     * flow-node IDs, text-annotation IDs, group IDs, sequence-flow IDs, and
     * association IDs directly from the input BPMN XML.
     */
    private fun semanticIds(xml: String): Pair<Set<String>, Set<String>> {
        val doc = parseXmlDoc(xml)
        val bpmnNs = "http://www.omg.org/spec/BPMN/20100524/MODEL"

        val shapeIds = mutableSetOf<String>()
        val edgeIds = mutableSetOf<String>()

        val shapeElements = listOf(
            "startEvent", "endEvent", "intermediateCatchEvent", "intermediateThrowEvent",
            "boundaryEvent", "userTask", "serviceTask", "sendTask", "receiveTask",
            "manualTask", "businessRuleTask", "scriptTask", "task",
            "callActivity", "subProcess",
            "exclusiveGateway", "parallelGateway", "inclusiveGateway",
            "eventBasedGateway", "complexGateway",
            "textAnnotation", "group",
        )
        for (tag in shapeElements) {
            val nodes = doc.getElementsByTagNameNS(bpmnNs, tag)
            for (i in 0 until nodes.length) {
                val id = (nodes.item(i) as org.w3c.dom.Element).getAttribute("id")
                if (id.isNotBlank()) shapeIds.add(id)
            }
        }

        for (tag in listOf("sequenceFlow", "association")) {
            val nodes = doc.getElementsByTagNameNS(bpmnNs, tag)
            for (i in 0 until nodes.length) {
                val id = (nodes.item(i) as org.w3c.dom.Element).getAttribute("id")
                if (id.isNotBlank()) edgeIds.add(id)
            }
        }

        return Pair(shapeIds, edgeIds)
    }

    private fun assertExactlyOneDiagram(xml: String) {
        val doc = parseXmlDoc(xml)
        val diNs = "http://www.omg.org/spec/BPMN/20100524/DI"
        val diagrams = doc.getElementsByTagNameNS(diNs, "BPMNDiagram")
        assertEquals(1, diagrams.length, "Expected exactly one BPMNDiagram but got ${diagrams.length}")
        val planes = doc.getElementsByTagNameNS(diNs, "BPMNPlane")
        assertEquals(1, planes.length, "Expected exactly one BPMNPlane but got ${planes.length}")
    }

    private fun assertPositiveBounds(xml: String) {
        val doc = parseXmlDoc(xml)
        val shapes = doc.getElementsByTagNameNS("http://www.omg.org/spec/BPMN/20100524/DI", "BPMNShape")
        for (i in 0 until shapes.length) {
            val shape = shapes.item(i) as org.w3c.dom.Element
            val shapeId = shape.getAttribute("bpmnElement")
            val boundsNodes = shape.getElementsByTagNameNS("http://www.omg.org/spec/DD/20100524/DC", "Bounds")
            if (boundsNodes.length > 0) {
                val bounds = boundsNodes.item(0) as org.w3c.dom.Element
                val w = bounds.getAttribute("width").toDoubleOrNull() ?: 0.0
                val h = bounds.getAttribute("height").toDoubleOrNull() ?: 0.0
                assertTrue(w > 0.0, "Shape '$shapeId' has non-positive width: $w")
                assertTrue(h > 0.0, "Shape '$shapeId' has non-positive height: $h")
            }
        }
    }

    private fun assertMinWaypoints(xml: String, minCount: Int) {
        val doc = parseXmlDoc(xml)
        val edges = doc.getElementsByTagNameNS("http://www.omg.org/spec/BPMN/20100524/DI", "BPMNEdge")
        for (i in 0 until edges.length) {
            val edge = edges.item(i) as org.w3c.dom.Element
            val edgeId = edge.getAttribute("bpmnElement")
            val waypoints = edge.getElementsByTagNameNS("http://www.omg.org/spec/DD/20100524/DI", "waypoint")
            assertTrue(
                waypoints.length >= minCount,
                "Edge '$edgeId' has only ${waypoints.length} waypoint(s); expected at least $minCount",
            )
        }
    }

    /** Canonical sorted list of shape/edge geometry strings for determinism comparison. */
    private fun diCoordinates(xml: String): List<String> {
        val doc = parseXmlDoc(xml)
        val results = mutableListOf<String>()
        val diNs = "http://www.omg.org/spec/BPMN/20100524/DI"
        val dcNs = "http://www.omg.org/spec/DD/20100524/DC"
        val diDiNs = "http://www.omg.org/spec/DD/20100524/DI"

        val shapes = doc.getElementsByTagNameNS(diNs, "BPMNShape")
        (0 until shapes.length)
            .map { shapes.item(it) as org.w3c.dom.Element }
            .sortedBy { it.getAttribute("bpmnElement") }
            .forEach { shape ->
                val bounds = shape.getElementsByTagNameNS(dcNs, "Bounds")
                if (bounds.length > 0) {
                    val b = bounds.item(0) as org.w3c.dom.Element
                    results.add(
                        "shape:${shape.getAttribute("bpmnElement")}:" +
                            "${b.getAttribute("x")},${b.getAttribute("y")}," +
                            "${b.getAttribute("width")},${b.getAttribute("height")}",
                    )
                }
            }

        val edges = doc.getElementsByTagNameNS(diNs, "BPMNEdge")
        (0 until edges.length)
            .map { edges.item(it) as org.w3c.dom.Element }
            .sortedBy { it.getAttribute("bpmnElement") }
            .forEach { edge ->
                val wps = edge.getElementsByTagNameNS(diDiNs, "waypoint")
                val coords = (0 until wps.length).joinToString(";") { i ->
                    val wp = wps.item(i) as org.w3c.dom.Element
                    "${wp.getAttribute("x")},${wp.getAttribute("y")}"
                }
                results.add("edge:${edge.getAttribute("bpmnElement")}:$coords")
            }

        return results
    }

    private fun parseXmlDoc(xml: String) = LayoutDiInspector.parse(xml)

    private fun loadCorpus(filename: String): String = LayoutDiInspector.loadCorpus(javaClass.classLoader, filename)

    private fun assertXml(xml: String): XmlAssert = XmlAssert.assertThat(xml).withNamespaceContext(
        mapOf(
            "bpmn" to "http://www.omg.org/spec/BPMN/20100524/MODEL",
            "bpmndi" to "http://www.omg.org/spec/BPMN/20100524/DI",
            "dc" to "http://www.omg.org/spec/DD/20100524/DC",
            "di" to "http://www.omg.org/spec/DD/20100524/DI",
        ),
    )

    private companion object {
        const val EVENT_SIZE = 36.0
    }
}
