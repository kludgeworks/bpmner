/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.inbound

import dev.groknull.bpmner.TestBpmnFixtures.testBpmnDefinition
import dev.groknull.bpmner.layout.LayoutedBpmnXml
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnLayoutService
import dev.groknull.bpmner.validation.BpmnXsdValidationPort
import dev.groknull.bpmner.validation.ValidatedBpmnXml
import dev.groknull.bpmner.validation.XsdValidationIssue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BpmnLayoutAgentTest {
    private fun buildLayoutAgent(
        xsdValidator: BpmnXsdValidationPort,
        layoutService: BpmnLayoutService = RecordingLayoutService(),
    ): BpmnLayoutAgent = BpmnLayoutAgent(layoutService, xsdValidator)

    // ---------------------------------------------------------------
    // autoFixBpmnXml: passthrough (bpmnlint auto-fix retired in #243)
    // ---------------------------------------------------------------

    @Test
    fun `auto-fix passes input through unchanged`() {
        val agent = buildLayoutAgent(RecordingXsdValidator(listOf(emptyList())))

        val definition = testBpmnDefinition()
        val result = agent.autoFixBpmnXml(ValidatedBpmnXml(definition, "<definitions />"))

        assertEquals("<definitions />", result.xml)
        assertEquals(definition, result.definition)
        assertNull(result.autoFixResult, "passthrough must not synthesize an auto-fix result")
    }

    // ---------------------------------------------------------------
    // layoutBpmnXml
    // ---------------------------------------------------------------

    @Test
    fun `layout invokes the layout service and threads the laid-out xml forward`() {
        val layoutService = RecordingLayoutService(listOf("<definitions laid-out=\"true\" />"))
        val agent = buildLayoutAgent(RecordingXsdValidator(listOf(emptyList())), layoutService)

        val definition = testBpmnDefinition()
        val autoFixed = agent.autoFixBpmnXml(ValidatedBpmnXml(definition, "<definitions />"))
        val laidOut = agent.layoutBpmnXml(autoFixed)

        assertEquals("<definitions laid-out=\"true\" />", laidOut.xml)
        assertEquals(listOf("<definitions />"), layoutService.xmls)
    }

    // ---------------------------------------------------------------
    // validateFinalBpmnXml
    // ---------------------------------------------------------------

    @Test
    fun `final validation passes when XSD is clean and DI is present`() {
        val xsdValidator = RecordingXsdValidator(listOf(emptyList()))
        val agent = buildLayoutAgent(xsdValidator)

        val definition = testBpmnDefinition()
        val validXml = """<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" id="def_1">
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
    ) : BpmnLayoutService() {
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
