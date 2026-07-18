/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.inbound

import dev.groknull.bpmner.TestBpmnFixtures.testBpmnDefinition
import dev.groknull.bpmner.conformance.BpmnXsdValidationPort
import dev.groknull.bpmner.conformance.ValidatedBpmnXml
import dev.groknull.bpmner.conformance.XsdValidationIssue
import dev.groknull.bpmner.layout.BpmnLayoutPort
import dev.groknull.bpmner.layout.LayoutedBpmnXml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BpmnLayoutAgentTest {
    private fun buildLayoutAgent(
        xsdValidator: BpmnXsdValidationPort,
        layoutService: BpmnLayoutPort = RecordingLayoutService(),
    ): BpmnLayoutAgent = BpmnLayoutAgent(layoutService, xsdValidator)

    @Test
    fun `layout invokes the layout service and threads the laid-out xml forward`() {
        val layoutService = RecordingLayoutService(listOf("<definitions laid-out=\"true\" />"))
        val agent = buildLayoutAgent(RecordingXsdValidator(listOf(emptyList())), layoutService)

        val definition = testBpmnDefinition()
        val laidOut = agent.layoutBpmnXml(ValidatedBpmnXml(definition, "<definitions />"))

        assertEquals("<definitions laid-out=\"true\" />", laidOut.xml)
        assertEquals(listOf("<definitions />"), layoutService.xmls)
    }

    @Test
    fun `final validation passes when XSD is clean and DI is present`() {
        val xsdValidator = RecordingXsdValidator(listOf(emptyList()))
        val agent = buildLayoutAgent(xsdValidator)

        val definition = testBpmnDefinition()
        val validXml = """<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" id="def_1">
  <startEvent id="StartEvent_1"/>
  <task id="Task_1"/>
  <endEvent id="EndEvent_1"/>
  <sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Task_1"/>
  <sequenceFlow id="Flow_2" sourceRef="Task_1" targetRef="EndEvent_1"/>
  <bpmndi:BPMNDiagram id="diag">
    <bpmndi:BPMNPlane id="plane">
      <bpmndi:BPMNShape bpmnElement="StartEvent_1" id="shape_start"/>
      <bpmndi:BPMNShape bpmnElement="Task_1" id="shape_task"/>
      <bpmndi:BPMNShape bpmnElement="EndEvent_1" id="shape_end"/>
      <bpmndi:BPMNEdge bpmnElement="Flow_1" id="edge_1"/>
      <bpmndi:BPMNEdge bpmnElement="Flow_2" id="edge_2"/>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>"""

        val result = agent.validateFinalBpmnXml(LayoutedBpmnXml(definition, validXml))

        assertEquals(validXml, result.xml)
        assertTrue(result.diagnostics.isEmpty())
        assertEquals(1, xsdValidator.xmls.size)
    }

    @Test
    fun `final validation throws BpmnLayoutCorruptionException on missing shapes`() {
        val xsdValidator = RecordingXsdValidator(listOf(emptyList()))
        val agent = buildLayoutAgent(xsdValidator)

        val definition = testBpmnDefinition()
        // Missing EndEvent_1 shape
        val invalidXml = """<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" id="def_1">
  <startEvent id="StartEvent_1"/>
  <task id="Task_1"/>
  <endEvent id="EndEvent_1"/>
  <sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Task_1"/>
  <sequenceFlow id="Flow_2" sourceRef="Task_1" targetRef="EndEvent_1"/>
  <bpmndi:BPMNDiagram id="diag">
    <bpmndi:BPMNPlane id="plane">
      <bpmndi:BPMNShape bpmnElement="StartEvent_1" id="shape_start"/>
      <bpmndi:BPMNShape bpmnElement="Task_1" id="shape_task"/>
      <bpmndi:BPMNEdge bpmnElement="Flow_1" id="edge_1"/>
      <bpmndi:BPMNEdge bpmnElement="Flow_2" id="edge_2"/>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>"""

        val error = assertFailsWith<BpmnLayoutCorruptionException> {
            agent.validateFinalBpmnXml(LayoutedBpmnXml(definition, invalidXml))
        }
        assertTrue(error.message!!.contains("Missing bpmndi:BPMNShape for flow nodes: [EndEvent_1]"))
    }

    @Test
    fun `final validation throws BpmnLayoutCorruptionException on DI referencing a nonexistent semantic element`() {
        val xsdValidator = RecordingXsdValidator(listOf(emptyList()))
        val agent = buildLayoutAgent(xsdValidator)

        val definition = testBpmnDefinition()
        // shape_task references "Ghost_1", which does not exist as a semantic element.
        val invalidXml = """<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" id="def_1">
  <bpmndi:BPMNDiagram id="diag">
    <bpmndi:BPMNPlane id="plane">
      <bpmndi:BPMNShape bpmnElement="StartEvent_1" id="shape_start"/>
      <bpmndi:BPMNShape bpmnElement="Ghost_1" id="shape_task"/>
      <bpmndi:BPMNShape bpmnElement="EndEvent_1" id="shape_end"/>
      <bpmndi:BPMNEdge bpmnElement="Flow_1" id="edge_1"/>
      <bpmndi:BPMNEdge bpmnElement="Flow_2" id="edge_2"/>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>"""

        val error = assertFailsWith<BpmnLayoutCorruptionException> {
            agent.validateFinalBpmnXml(LayoutedBpmnXml(definition, invalidXml))
        }
        assertTrue(error.message!!.contains("DI elements reference nonexistent semantic elements"))
        assertTrue(error.message!!.contains("Ghost_1"))
    }

    @Test
    fun `final validation throws BpmnLayoutCorruptionException on two DI elements referencing the same semantic element`() {
        val xsdValidator = RecordingXsdValidator(listOf(emptyList()))
        val agent = buildLayoutAgent(xsdValidator)

        val definition = testBpmnDefinition()
        // shape_start and shape_dup both reference StartEvent_1.
        val invalidXml = """<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" id="def_1">
  <startEvent id="StartEvent_1"/>
  <task id="Task_1"/>
  <endEvent id="EndEvent_1"/>
  <bpmndi:BPMNDiagram id="diag">
    <bpmndi:BPMNPlane id="plane">
      <bpmndi:BPMNShape bpmnElement="StartEvent_1" id="shape_start"/>
      <bpmndi:BPMNShape bpmnElement="StartEvent_1" id="shape_dup"/>
      <bpmndi:BPMNShape bpmnElement="Task_1" id="shape_task"/>
      <bpmndi:BPMNShape bpmnElement="EndEvent_1" id="shape_end"/>
      <bpmndi:BPMNEdge bpmnElement="Flow_1" id="edge_1"/>
      <bpmndi:BPMNEdge bpmnElement="Flow_2" id="edge_2"/>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>"""

        val error = assertFailsWith<BpmnLayoutCorruptionException> {
            agent.validateFinalBpmnXml(LayoutedBpmnXml(definition, invalidXml))
        }
        assertTrue(error.message!!.contains("Multiple DI elements reference the same semantic element"))
        assertTrue(error.message!!.contains("StartEvent_1"))
    }

    @Test
    fun `final validation throws BpmnLayoutCorruptionException on a sequence flow endpoint without DI`() {
        val xsdValidator = RecordingXsdValidator(listOf(emptyList()))
        val agent = buildLayoutAgent(xsdValidator)

        val definition = testBpmnDefinition()
        // Flow_3 is not part of the definition's own coverage requirement, so it exercises
        // only the new endpoint-resolution rule: its targetRef ("Ghost_Task") resolves
        // semantically but was never drawn (no bpmndi:BPMNShape).
        val invalidXml = """<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" id="def_1">
  <startEvent id="StartEvent_1"/>
  <task id="Task_1"/>
  <endEvent id="EndEvent_1"/>
  <task id="Ghost_Task"/>
  <sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Task_1"/>
  <sequenceFlow id="Flow_2" sourceRef="Task_1" targetRef="EndEvent_1"/>
  <sequenceFlow id="Flow_3" sourceRef="Task_1" targetRef="Ghost_Task"/>
  <bpmndi:BPMNDiagram id="diag">
    <bpmndi:BPMNPlane id="plane">
      <bpmndi:BPMNShape bpmnElement="StartEvent_1" id="shape_start"/>
      <bpmndi:BPMNShape bpmnElement="Task_1" id="shape_task"/>
      <bpmndi:BPMNShape bpmnElement="EndEvent_1" id="shape_end"/>
      <bpmndi:BPMNEdge bpmnElement="Flow_1" id="edge_1"/>
      <bpmndi:BPMNEdge bpmnElement="Flow_2" id="edge_2"/>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>"""

        val error = assertFailsWith<BpmnLayoutCorruptionException> {
            agent.validateFinalBpmnXml(LayoutedBpmnXml(definition, invalidXml))
        }
        assertTrue(error.message!!.contains("Edge endpoints reference elements without DI"))
        assertTrue(error.message!!.contains("Ghost_Task"))
    }

    @Test
    fun `final validation throws BpmnLayoutCorruptionException on a boundary event attached to a host without DI`() {
        val xsdValidator = RecordingXsdValidator(listOf(emptyList()))
        val agent = buildLayoutAgent(xsdValidator)

        val definition = testBpmnDefinition()
        // BoundaryEvent_1 attaches to Task_1, which resolves semantically but has no DI shape.
        val invalidXml = """<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" id="def_1">
  <startEvent id="StartEvent_1"/>
  <task id="Task_1"/>
  <endEvent id="EndEvent_1"/>
  <boundaryEvent id="BoundaryEvent_1" attachedToRef="Task_1"/>
  <sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Task_1"/>
  <sequenceFlow id="Flow_2" sourceRef="Task_1" targetRef="EndEvent_1"/>
  <bpmndi:BPMNDiagram id="diag">
    <bpmndi:BPMNPlane id="plane">
      <bpmndi:BPMNShape bpmnElement="StartEvent_1" id="shape_start"/>
      <bpmndi:BPMNShape bpmnElement="EndEvent_1" id="shape_end"/>
      <bpmndi:BPMNShape bpmnElement="BoundaryEvent_1" id="shape_boundary"/>
      <bpmndi:BPMNEdge bpmnElement="Flow_1" id="edge_1"/>
      <bpmndi:BPMNEdge bpmnElement="Flow_2" id="edge_2"/>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>"""

        val error = assertFailsWith<BpmnLayoutCorruptionException> {
            agent.validateFinalBpmnXml(LayoutedBpmnXml(definition, invalidXml))
        }
        assertTrue(error.message!!.contains("Boundary events attach to hosts without DI"))
        assertTrue(error.message!!.contains("Task_1"))
    }

    @Test
    fun `final validation throws BpmnLayoutCorruptionException on XSD failure`() {
        val xsdValidator =
            RecordingXsdValidator(
                listOf(listOf(XsdValidationIssue("cvc-complex-type failure", "Task_1"))),
            )
        val agent = buildLayoutAgent(xsdValidator)

        val definition = testBpmnDefinition()
        val error =
            assertFailsWith<BpmnLayoutCorruptionException> {
                agent.validateFinalBpmnXml(LayoutedBpmnXml(definition, "<definitions />"))
            }
        assertTrue(error.message!!.contains("Auto-layout produced structurally invalid BPMN"))
        assertTrue(error.message!!.contains("cvc-complex-type failure"))
    }

    private class RecordingLayoutService(
        private val responses: List<String> = emptyList(),
    ) : BpmnLayoutPort {
        val xmls = mutableListOf<String>()
        private var index = 0

        override fun layout(xml: String): String {
            xmls += xml
            return if (index < responses.size) responses[index++] else xml
        }
    }

    private class RecordingXsdValidator(
        private val responses: List<List<XsdValidationIssue>>,
    ) : BpmnXsdValidationPort {
        val xmls = mutableListOf<String>()
        private var index = 0

        override fun validateDetailed(bpmnXml: String): List<XsdValidationIssue> {
            xmls += bpmnXml
            return responses[index++]
        }
    }
}
