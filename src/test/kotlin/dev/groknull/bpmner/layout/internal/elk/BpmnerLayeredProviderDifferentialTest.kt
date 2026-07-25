/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.elk

import dev.groknull.bpmner.layout.internal.BpmnPlacementPass
import dev.groknull.bpmner.layout.internal.BpmnToElkMapper
import dev.groknull.bpmner.layout.internal.ElkBpmnLayouter
import dev.groknull.bpmner.layout.internal.ElkToBpmnDiWriter
import dev.groknull.bpmner.layout.internal.LayoutDiInspector
import org.camunda.bpm.model.bpmn.Bpmn
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnDiagram
import org.eclipse.elk.alg.layered.options.LayeredOptions
import org.eclipse.elk.core.RecursiveGraphLayoutEngine
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.core.util.BasicProgressMonitor
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals

/**
 * AD-622-18's standing regression guard: [BpmnerLayeredProvider] with an empty injection list
 * must produce byte-identical BPMN DI to stock [org.eclipse.elk.alg.layered.LayeredLayoutProvider]
 * for every fixture in the 24-fixture corpus. Any divergence means the carrier's re-derivation of
 * `LayeredLayoutProvider.layout()` (transformer import, hierarchy branch, `doLayout`/
 * `doCompoundLayout`, `applyLayout`) has drifted from the stock method it re-implements.
 *
 * Runs the exact same [BpmnToElkMapper] → [RecursiveGraphLayoutEngine] → [BpmnPlacementPass] →
 * [ElkToBpmnDiWriter] pipeline [ElkBpmnLayouter] uses in production, swapping only the ELK
 * skeleton's `ALGORITHM` property between the stock id ([LayeredOptions.ALGORITHM_ID]) and the
 * carrier's ([BPMNER_LAYERED_ALGORITHM_ID]) before layout runs.
 */
class BpmnerLayeredProviderDifferentialTest {

    init {
        ElkBpmnLayouter().registerElkLayoutAlgorithm()
    }

    @ParameterizedTest(name = "byte-identical to stock LayeredLayoutProvider: {0}")
    @ValueSource(
        strings = [
            "representative-process",
            "explicit-cycle",
            "long-labels",
            "annotation-and-group",
            "boundary-timer-task",
            "boundary-on-subprocess",
            "boundary-error-task",
            "boundary-multi",
            "subprocess-flat",
            "subprocess-loop",
            "subprocess-branch",
            "subprocess-nested",
            "subprocess-no-start-cycle",
            "subprocess-sequential-sharing",
            "collab-lanes",
            "collab-two-pools",
            "collab-blackbox",
            "collab-msg-endpoint",
            "collab-msg-label",
            "collab-subprocess",
            "collab-bioc",
            "collab-lanes-loopback",
            "miwg-a2-1",
            "miwg-a3-0",
        ],
    )
    fun `carrier is production-neutral against stock layered`(fixture: String) {
        val xml = LayoutDiInspector.loadCorpus(javaClass.classLoader, "$fixture.bpmn")
        val stock = runLayout(xml, LayeredOptions.ALGORITHM_ID)
        val carrier = runLayout(xml, BPMNER_LAYERED_ALGORITHM_ID)
        assertEquals(
            stock,
            carrier,
            "'$fixture': BpmnerLayeredProvider(emptyList()) diverged from stock " +
                "LayeredLayoutProvider — the carrier is no longer production-neutral.",
        )
    }

    /**
     * Runs the production layout pipeline, forcing the ELK skeleton's algorithm to
     * [algorithmId] before [RecursiveGraphLayoutEngine] dispatches on it.
     */
    private fun runLayout(xml: String, algorithmId: String): String {
        val model = Bpmn.readModelFromStream(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val existingShapes = ElkToBpmnDiWriter.captureExistingShapes(model)
        val existingEdges = ElkToBpmnDiWriter.captureExistingEdges(model)
        model.getModelElementsByType(BpmnDiagram::class.java).toList()
            .forEach { model.definitions.removeChildElement(it) }
        val skeleton = BpmnToElkMapper.map(model)
        skeleton.root.setProperty(CoreOptions.ALGORITHM, algorithmId)
        RecursiveGraphLayoutEngine().layout(skeleton.root, BasicProgressMonitor())
        val placed = BpmnPlacementPass.place(model, skeleton)
        ElkToBpmnDiWriter.write(model, placed, existingShapes, existingEdges)
        val out = ByteArrayOutputStream()
        Bpmn.writeModelToStream(out, model)
        return out.toString(Charsets.UTF_8).lines().joinToString("\n") { it.trimEnd() }
    }
}
