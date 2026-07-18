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
    // Suppressed because splitting the sequential accumulate-then-throw validation checks into
    // smaller methods would scatter the single `errors` list they all append to.
    @Suppress("LongMethod")
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

            val shapeBpmnElementRefs = (0 until shapes.length)
                .map { (shapes.item(it) as org.w3c.dom.Element).getAttribute("bpmnElement") }
            val shapeBpmnElements = shapeBpmnElementRefs.toSet()

            val edges = doc.getElementsByTagNameNS("http://www.omg.org/spec/BPMN/20100524/DI", "BPMNEdge")
            val edgeBpmnElementRefs = (0 until edges.length)
                .map { (edges.item(it) as org.w3c.dom.Element).getAttribute("bpmnElement") }
            val edgeBpmnElements = edgeBpmnElementRefs.toSet()

            val missingNodeShapes = bpmn.definition.nodes.map { it.id }.filter { !shapeBpmnElements.contains(it) }
            if (missingNodeShapes.isNotEmpty()) {
                errors.add("Missing bpmndi:BPMNShape for flow nodes: $missingNodeShapes")
            }

            val missingSequenceEdges = bpmn.definition.sequences.map { it.id }.filter { !edgeBpmnElements.contains(it) }
            if (missingSequenceEdges.isNotEmpty()) {
                errors.add("Missing bpmndi:BPMNEdge for sequence flows: $missingSequenceEdges")
            }

            errors.addAll(
                referentialIntegrityErrors(doc, shapeBpmnElements, edgeBpmnElements, shapeBpmnElementRefs + edgeBpmnElementRefs),
            )
        }

        if (errors.isNotEmpty()) {
            throw BpmnLayoutCorruptionException(errors.joinToString("\n"))
        }

        logger.info("Final BPMN XSD validation passed after auto-layout")
        return FinalValidatedBpmnXml(definition = bpmn.definition, xml = bpmn.xml)
    }
}

private fun XsdValidationIssue.summary(): String = listOfNotNull(elementId, message).joinToString("|")

private const val BPMN_MODEL_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL"

/**
 * Runs the DI-to-semantic uniqueness, edge-endpoint, and boundary-host referential-integrity
 * checks that bpmn-js's importer enforces (`BpmnTreeWalker.registerDi`/`_getConnectedElement`/
 * `_attachBoundary`) but silently demotes to non-throwing warnings.
 */
internal fun referentialIntegrityErrors(xml: String): List<String> {
    val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(xml)))
    val shapeBpmnElementRefs = doc.diBpmnElementRefs("BPMNShape")
    val edgeBpmnElementRefs = doc.diBpmnElementRefs("BPMNEdge")
    return referentialIntegrityErrors(
        doc,
        shapeBpmnElementRefs.toSet(),
        edgeBpmnElementRefs.toSet(),
        shapeBpmnElementRefs + edgeBpmnElementRefs,
    )
}

private fun org.w3c.dom.Document.diBpmnElementRefs(diTag: String): List<String> {
    val elements = getElementsByTagNameNS("http://www.omg.org/spec/BPMN/20100524/DI", diTag)
    return (0 until elements.length).map { (elements.item(it) as org.w3c.dom.Element).getAttribute("bpmnElement") }
}

private fun referentialIntegrityErrors(
    doc: org.w3c.dom.Document,
    shapeBpmnElements: Set<String>,
    edgeBpmnElements: Set<String>,
    drawnElementRefs: List<String>,
): List<String> {
    val drawnElements = shapeBpmnElements + edgeBpmnElements
    val errors = mutableListOf<String>()
    errors.addAll(diResolutionErrors(doc, drawnElementRefs))
    errors.addAll(edgeEndpointErrors(doc, drawnElements))
    errors.addAll(boundaryHostErrors(doc, shapeBpmnElements))
    return errors
}

/** Every DI element's bpmnElement resolves to a real semantic element, and no two DI elements share one. */
private fun diResolutionErrors(
    doc: org.w3c.dom.Document,
    drawnElementRefs: List<String>,
): List<String> {
    val semanticElementIds = mutableSetOf<String>()
    val semanticElements = doc.getElementsByTagNameNS(BPMN_MODEL_NS, "*")
    for (i in 0 until semanticElements.length) {
        val id = (semanticElements.item(i) as org.w3c.dom.Element).getAttribute("id")
        if (id.isNotEmpty()) semanticElementIds.add(id)
    }

    val seenBpmnElements = mutableSetOf<String>()
    val duplicateBpmnElements = mutableSetOf<String>()
    val unresolvedDiReferences = mutableSetOf<String>()
    for (bpmnElement in drawnElementRefs) {
        if (bpmnElement !in semanticElementIds) unresolvedDiReferences.add(bpmnElement)
        if (!seenBpmnElements.add(bpmnElement)) duplicateBpmnElements.add(bpmnElement)
    }

    return buildList {
        if (unresolvedDiReferences.isNotEmpty()) {
            add("DI elements reference nonexistent semantic elements: $unresolvedDiReferences")
        }
        if (duplicateBpmnElements.isNotEmpty()) {
            add("Multiple DI elements reference the same semantic element: $duplicateBpmnElements")
        }
    }
}

/** Every sequenceFlow/messageFlow/association's sourceRef/targetRef resolves to a semantic element that has DI. */
private fun edgeEndpointErrors(doc: org.w3c.dom.Document, drawnElements: Set<String>): List<String> {
    val unresolvedEndpoints = mutableSetOf<String>()
    for (edgeTag in listOf("sequenceFlow", "messageFlow", "association")) {
        val flowElements = doc.getElementsByTagNameNS(BPMN_MODEL_NS, edgeTag)
        for (i in 0 until flowElements.length) {
            val flow = flowElements.item(i) as org.w3c.dom.Element
            val sourceRef = flow.getAttribute("sourceRef")
            val targetRef = flow.getAttribute("targetRef")
            if (sourceRef.isNotEmpty() && sourceRef !in drawnElements) unresolvedEndpoints.add(sourceRef)
            if (targetRef.isNotEmpty() && targetRef !in drawnElements) unresolvedEndpoints.add(targetRef)
        }
    }
    return if (unresolvedEndpoints.isNotEmpty()) {
        listOf("Edge endpoints reference elements without DI: $unresolvedEndpoints")
    } else {
        emptyList()
    }
}

/** Every boundary event's attachedToRef resolves to a semantic element that has DI. */
private fun boundaryHostErrors(doc: org.w3c.dom.Document, shapeBpmnElements: Set<String>): List<String> {
    val unresolvedHosts = mutableSetOf<String>()
    val boundaryEvents = doc.getElementsByTagNameNS(BPMN_MODEL_NS, "boundaryEvent")
    for (i in 0 until boundaryEvents.length) {
        val attachedToRef = (boundaryEvents.item(i) as org.w3c.dom.Element).getAttribute("attachedToRef")
        if (attachedToRef.isNotEmpty() && attachedToRef !in shapeBpmnElements) {
            unresolvedHosts.add(attachedToRef)
        }
    }
    return if (unresolvedHosts.isNotEmpty()) {
        listOf("Boundary events attach to hosts without DI: $unresolvedHosts")
    } else {
        emptyList()
    }
}

class BpmnLayoutCorruptionException(
    message: String,
) : IllegalStateException(message)
