/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import dev.groknull.bpmner.ruleset.BpmnerLintConfig
import dev.groknull.bpmner.ruleset.ShapeStyle
import dev.groknull.bpmner.ruleset.ThemeConfig
import org.junit.jupiter.api.Test
import org.xmlunit.assertj.XmlAssert

/**
 * Verifies [ThemeDecorator] writes the active [ThemeConfig] onto generated BPMN DI in both the
 * `bioc:` and `color:` namespace families, that [ThemeConfig.shapeOverrides] beat the global
 * fallback, and that pre-existing colours ([DIMergeTest]'s author-preservation contract) are
 * never overwritten. A loader-level test proving the theme parses (see [ConventionsLoaderTest]
 * for #717) is not sufficient evidence the consumer actually reads it — this test exercises a
 * non-default theme end to end through [ElkBpmnLayouter].
 */
class ThemeDecoratorTest {

    private val bpmnNs = mapOf(
        "bpmn" to "http://www.omg.org/spec/BPMN/20100524/MODEL",
        "bpmndi" to "http://www.omg.org/spec/BPMN/20100524/DI",
        "dc" to "http://www.omg.org/spec/DD/20100524/DC",
        "bioc" to "http://bpmn.io/schema/bpmn/biocolor/1.0",
        "color" to "http://www.omg.org/spec/BPMN/non-normative/color/1.0",
    )

    private fun assertXml(xml: String): XmlAssert = XmlAssert.assertThat(xml).withNamespaceContext(bpmnNs)

    private val xmlWithoutDi = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                  id="Definitions_theme" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:process id="Process_1" isExecutable="true">
    <bpmn:startEvent id="Start_1"><bpmn:outgoing>Flow_1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:task id="Task_1">
      <bpmn:incoming>Flow_1</bpmn:incoming>
      <bpmn:outgoing>Flow_2</bpmn:outgoing>
    </bpmn:task>
    <bpmn:endEvent id="End_1"><bpmn:incoming>Flow_2</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="Flow_1" sourceRef="Start_1" targetRef="Task_1"/>
    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_1" targetRef="End_1"/>
  </bpmn:process>
</bpmn:definitions>"""

    @Test
    fun `global theme colors are applied to shapes and edges in both namespaces`() {
        val theme = ThemeConfig(secondaryColor = "#112233", backgroundColor = "#445566")
        val layouter = ElkBpmnLayouter(BpmnerLintConfig(theme = theme)).apply { registerElkLayoutAlgorithm() }

        val result = layouter.layout(xmlWithoutDi)

        assertXml(result)
            .nodesByXPath("//bpmndi:BPMNShape[@bpmnElement='Start_1'][@bioc:fill='#445566'][@bioc:stroke='#112233']")
            .exist()
        assertXml(result)
            .nodesByXPath(
                "//bpmndi:BPMNShape[@bpmnElement='Start_1']" +
                    "[@color:background-color='#445566'][@color:border-color='#112233']",
            )
            .exist()
        assertXml(result)
            .nodesByXPath("//bpmndi:BPMNEdge[@bpmnElement='Flow_1'][@bioc:stroke='#112233']")
            .exist()
        assertXml(result)
            .nodesByXPath("//bpmndi:BPMNEdge[@bpmnElement='Flow_1'][@color:border-color='#112233']")
            .exist()
        assertXml(result)
            .nodesByXPath("//bpmndi:BPMNEdge[@bpmnElement='Flow_1'][@bioc:fill]")
            .doNotExist()
    }

    @Test
    fun `shapeOverrides beat the global fallback`() {
        val theme = ThemeConfig(
            secondaryColor = "#112233",
            backgroundColor = "#445566",
            shapeOverrides = mapOf("bpmn:Task" to ShapeStyle(fill = "#00ff00", stroke = "#ff0000")),
        )
        val layouter = ElkBpmnLayouter(BpmnerLintConfig(theme = theme)).apply { registerElkLayoutAlgorithm() }

        val result = layouter.layout(xmlWithoutDi)

        assertXml(result)
            .nodesByXPath("//bpmndi:BPMNShape[@bpmnElement='Task_1'][@bioc:fill='#00ff00'][@bioc:stroke='#ff0000']")
            .exist()
        assertXml(result)
            .nodesByXPath("//bpmndi:BPMNShape[@bpmnElement='Start_1'][@bioc:fill='#445566']")
            .exist()
    }

    @Test
    fun `pre-existing shape color is not overwritten by the theme`() {
        val xmlWithColor = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                  xmlns:bioc="http://bpmn.io/schema/bpmn/biocolor/1.0"
                  id="Definitions_theme_existing" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:process id="Process_1" isExecutable="true">
    <bpmn:startEvent id="Start_1"><bpmn:outgoing>Flow_1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:endEvent id="End_1"><bpmn:incoming>Flow_1</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="Flow_1" sourceRef="Start_1" targetRef="End_1"/>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="Diagram_1">
    <bpmndi:BPMNPlane id="Plane_1" bpmnElement="Process_1">
      <bpmndi:BPMNShape id="Shape_1" bpmnElement="Start_1" bioc:fill="#abcdef" bioc:stroke="#fedcba">
        <dc:Bounds x="0" y="0" width="36" height="36"/>
      </bpmndi:BPMNShape>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>"""
        val theme = ThemeConfig(secondaryColor = "#112233", backgroundColor = "#445566")
        val layouter = ElkBpmnLayouter(BpmnerLintConfig(theme = theme)).apply { registerElkLayoutAlgorithm() }

        val result = layouter.layout(xmlWithColor)

        assertXml(result)
            .nodesByXPath("//bpmndi:BPMNShape[@bpmnElement='Start_1'][@bioc:fill='#abcdef'][@bioc:stroke='#fedcba']")
            .exist()
    }
}
