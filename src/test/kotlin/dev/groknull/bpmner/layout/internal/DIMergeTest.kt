/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import org.junit.jupiter.api.Test
import org.xmlunit.assertj.XmlAssert

/**
 * Contract tests for the DI-merge gate.
 *
 * Verifies that [ElkBpmnLayouter] preserves non-geometry DI attributes
 * (bioc: colour extensions) when re-laying-out a model that already has DI.
 * The golden-file gate ([ElkGoldenLayoutTest]) is the byte-exact outer ring;
 * this test is the targeted behaviour check.
 */
class DIMergeTest {

    private val layouter = ElkBpmnLayouter()

    private val bpmnNs = mapOf(
        "bpmn" to "http://www.omg.org/spec/BPMN/20100524/MODEL",
        "bpmndi" to "http://www.omg.org/spec/BPMN/20100524/DI",
        "dc" to "http://www.omg.org/spec/DD/20100524/DC",
        "di" to "http://www.omg.org/spec/DD/20100524/DI",
        "bioc" to "http://bpmn.io/schema/bpmn/biocolor/1.0",
    )

    private fun assertXml(xml: String): XmlAssert =
        XmlAssert.assertThat(xml).withNamespaceContext(bpmnNs)

    private fun loadFixture(name: String): String =
        javaClass.classLoader.getResourceAsStream("layout-fixtures/$name")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Fixture not found: layout-fixtures/$name")

    @Test
    fun `bioc stroke and fill survive round-trip re-layout`() {
        val result = layouter.layout(loadFixture("collab-bioc.bpmn"))

        assertXml(result)
            .nodesByXPath("//bpmndi:BPMNShape[@bpmnElement='Start_1'][@bioc:stroke='#005b1d']")
            .exist()
        assertXml(result)
            .nodesByXPath("//bpmndi:BPMNShape[@bpmnElement='Start_1'][@bioc:fill='#b5efcd']")
            .exist()
        assertXml(result)
            .nodesByXPath("//bpmndi:BPMNShape[@bpmnElement='End_1'][@bioc:stroke='#831311']")
            .exist()
        assertXml(result)
            .nodesByXPath("//bpmndi:BPMNShape[@bpmnElement='End_1'][@bioc:fill='#ffcdd2']")
            .exist()
    }

    @Test
    fun `re-layout produces exactly one diagram`() {
        val result = layouter.layout(loadFixture("collab-bioc.bpmn"))

        assertXml(result)
            .nodesByXPath("//bpmndi:BPMNDiagram")
            .hasSize(1)
    }
}
