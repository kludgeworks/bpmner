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
 * Stateless ELK layout path for retained BPMN processes including subprocesses
 * and boundary events.
 *
 * Not annotated with @Service or @SecondaryAdapter; not wired into BpmnLayoutPort.
 * The GraalJS BpmnLayoutService is the sole production layout authority (AD-557-08).
 *
 * Implements the AD-557-10 skeleton-then-refine pipeline:
 *   Phase 1a: [BpmnToElkMapper] builds a LEAN ELK graph (no labels; boundary events
 *             as SOUTH ports).
 *   Phase 1b: [RecursiveGraphLayoutEngine] runs ELK; output coordinates are immutable.
 *   Phase 2:  [BpmnPlacementPass] applies BPMN placement conventions as named rules
 *             (boundary shapes on host bottom, labels below nodes, baseline snap, etc.).
 *   Phase 3:  [ElkToBpmnDiWriter] serialises the [BpmnPlacementPass.PlacedLayout] into
 *             Camunda BPMN-DI.
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
        // Phase 1a: build lean ELK skeleton
        val skeleton = BpmnToElkMapper.map(model)
        // Phase 1b: run ELK; skeleton.root now contains layout coordinates
        RecursiveGraphLayoutEngine().layout(skeleton.root, BasicProgressMonitor())
        // Phase 2: apply BPMN placement conventions
        val placed = BpmnPlacementPass.place(model, skeleton)
        // Phase 3: write DI from PlacedLayout
        ElkToBpmnDiWriter.write(model, placed)
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
            .lineSequence()
            .map { it.trimEnd() }
            .joinToString("\n")
    } catch (e: java.io.IOException) {
        throw BpmnAutoLayoutException("ELK layout failed: could not serialize BPMN XML: ${e.message}", e)
    }

    private fun removeExistingDi(model: BpmnModelInstance) {
        model.getModelElementsByType(BpmnDiagram::class.java)
            .toList()
            .forEach { model.definitions.removeChildElement(it) }
    }
}
