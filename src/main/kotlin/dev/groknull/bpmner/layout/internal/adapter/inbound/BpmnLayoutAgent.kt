/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.inbound

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.Export
import dev.groknull.bpmner.conformance.BpmnXsdValidationPort
import dev.groknull.bpmner.conformance.FinalValidatedBpmnXml
import dev.groknull.bpmner.conformance.ValidatedBpmnXml
import dev.groknull.bpmner.conformance.XsdValidationIssue
import dev.groknull.bpmner.layout.BpmnLayoutPort
import dev.groknull.bpmner.layout.LayoutedBpmnXml
import org.jmolecules.architecture.onion.simplified.InfrastructureRing
import org.slf4j.LoggerFactory

/**
 * Owns the post-repair pipeline: auto-layout and final XSD validation.
 */
@InfrastructureRing
@Agent(description = "Apply auto-layout and final validation to validated BPMN XML")
internal class BpmnLayoutAgent(
    private val layoutService: BpmnLayoutPort,
    private val bpmnXsdValidationPort: BpmnXsdValidationPort,
) {
    private val logger = LoggerFactory.getLogger(BpmnLayoutAgent::class.java)

    @Action(description = "Apply auto-layout to the validated BPMN XML")
    fun layoutBpmnXml(bpmn: ValidatedBpmnXml): LayoutedBpmnXml {
        val layoutedXml = layoutService.layout(bpmn.xml)
        return LayoutedBpmnXml(definition = bpmn.definition, xml = layoutedXml)
    }

    @AchievesGoal(
        description = "Apply auto-layout and final validation to validated BPMN XML",
        export = Export(name = "finalizeLayout", startingInputTypes = [LayoutedBpmnXml::class]),
    )
    @Action(description = "XSD-validate the final layouted BPMN XML")
    fun validateFinalBpmnXml(bpmn: LayoutedBpmnXml): FinalValidatedBpmnXml {
        val errors = mutableListOf<String>()
        val xsdIssues = bpmnXsdValidationPort.validateDetailed(bpmn.xml)
        if (xsdIssues.isNotEmpty()) {
            errors.add(
                "Auto-layout produced structurally invalid BPMN: " +
                    xsdIssues.joinToString("; ") { it.summary() },
            )
        }

        val doc = try {
            javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
            }.newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(bpmn.xml)))
        } catch (e: org.xml.sax.SAXException) {
            errors.add("Failed to parse layouted XML: ${e.message}")
            null
        } catch (e: java.io.IOException) {
            errors.add("Failed to parse layouted XML: ${e.message}")
            null
        }

        if (doc != null) {
            val diagrams = doc.getElementsByTagNameNS("http://www.omg.org/spec/BPMN/20100524/DI", "BPMNDiagram")
            if (diagrams.length != 1) {
                errors.add("Final XML must contain exactly one bpmndi:BPMNDiagram")
            }

            val planes = doc.getElementsByTagNameNS("http://www.omg.org/spec/BPMN/20100524/DI", "BPMNPlane")
            if (planes.length != 1) {
                errors.add("Final XML must contain exactly one bpmndi:BPMNPlane")
            }

            val shapes = doc.getElementsByTagNameNS("http://www.omg.org/spec/BPMN/20100524/DI", "BPMNShape")
            if (shapes.length == 0) {
                errors.add("Final XML must contain at least one bpmndi:BPMNShape")
            }

            val shapeBpmnElements = mutableSetOf<String>()
            for (i in 0 until shapes.length) {
                val shape = shapes.item(i) as org.w3c.dom.Element
                shapeBpmnElements.add(shape.getAttribute("bpmnElement"))
            }

            val edges = doc.getElementsByTagNameNS("http://www.omg.org/spec/BPMN/20100524/DI", "BPMNEdge")
            val edgeBpmnElements = mutableSetOf<String>()
            for (i in 0 until edges.length) {
                val edge = edges.item(i) as org.w3c.dom.Element
                edgeBpmnElements.add(edge.getAttribute("bpmnElement"))
            }

            val missingNodeShapes = bpmn.definition.nodes.map { it.id }.filter { !shapeBpmnElements.contains(it) }
            if (missingNodeShapes.isNotEmpty()) {
                errors.add("Missing bpmndi:BPMNShape for flow nodes: $missingNodeShapes")
            }

            val missingSequenceEdges = bpmn.definition.sequences.map { it.id }.filter { !edgeBpmnElements.contains(it) }
            if (missingSequenceEdges.isNotEmpty()) {
                errors.add("Missing bpmndi:BPMNEdge for sequence flows: $missingSequenceEdges")
            }
        }

        if (errors.isNotEmpty()) {
            throw BpmnLayoutCorruptionException(errors.joinToString("\n"))
        }

        logger.info("Final BPMN XSD validation passed after auto-layout")
        return FinalValidatedBpmnXml(definition = bpmn.definition, xml = bpmn.xml)
    }
}

private fun XsdValidationIssue.summary(): String = listOfNotNull(elementId, message).joinToString("|")

class BpmnLayoutCorruptionException(
    message: String,
) : IllegalStateException(message)
