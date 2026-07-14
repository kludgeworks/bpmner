/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import dev.groknull.bpmner.layout.BpmnAutoLayoutException
import org.camunda.bpm.model.bpmn.Bpmn
import org.camunda.bpm.model.bpmn.BpmnModelInstance
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnDiagram
import org.eclipse.elk.alg.layered.options.LayeredMetaDataProvider
import org.eclipse.elk.core.RecursiveGraphLayoutEngine
import org.eclipse.elk.core.data.LayoutMetaDataService
import org.eclipse.elk.core.util.BasicProgressMonitor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Stateless ELK layout path for flat retained BPMN processes.
 *
 * Not annotated with @Service; not wired into BpmnLayoutPort.
 * The GraalJS BpmnLayoutService is the sole production layout authority.
 *
 * Parses the input XML, removes existing BPMN-DI, runs ELK Layered layout via
 * [BpmnToElkMapper] and [ElkToBpmnDiWriter], then serializes the result.
 */
internal class ElkBpmnLayouter {

    init {
        // ELK requires algorithm registration outside OSGi. LayoutMetaDataService is a
        // singleton that ignores duplicate registrations, so construction-time registration is safe.
        LayoutMetaDataService.getInstance().registerLayoutMetaDataProviders(LayeredMetaDataProvider())
    }

    @Suppress("ThrowsCount") // parse, layout, and serialize are three distinct failure modes
    fun layout(xml: String): String {
        val model = parseXml(xml)
        removeExistingDi(model)
        val (elkRoot, nodeMap, edgeMap) = BpmnToElkMapper.map(model)
        RecursiveGraphLayoutEngine().layout(elkRoot, BasicProgressMonitor())
        ElkToBpmnDiWriter.write(model, nodeMap, edgeMap)
        return serializeXml(model)
    }

    private fun parseXml(xml: String): BpmnModelInstance = try {
        Bpmn.readModelFromStream(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    } catch (e: org.camunda.bpm.model.xml.ModelParseException) {
        throw BpmnAutoLayoutException("ELK layout failed: could not parse BPMN XML: ${e.message}", e)
    }

    private fun serializeXml(model: BpmnModelInstance): String = try {
        val out = ByteArrayOutputStream()
        Bpmn.writeModelToStream(out, model)
        out.toString(Charsets.UTF_8)
    } catch (e: java.io.IOException) {
        throw BpmnAutoLayoutException("ELK layout failed: could not serialize BPMN XML: ${e.message}", e)
    }

    private fun removeExistingDi(model: BpmnModelInstance) {
        model.getModelElementsByType(BpmnDiagram::class.java)
            .toList()
            .forEach { model.definitions.removeChildElement(it) }
    }
}
