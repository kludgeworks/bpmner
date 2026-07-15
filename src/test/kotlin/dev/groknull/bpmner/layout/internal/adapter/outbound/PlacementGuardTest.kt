/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import dev.groknull.bpmner.layout.internal.adapter.outbound.placement.PlacementContext
import org.eclipse.elk.alg.layered.options.LayeredMetaDataProvider
import org.eclipse.elk.core.RecursiveGraphLayoutEngine
import org.eclipse.elk.core.data.LayoutMetaDataService
import org.eclipse.elk.core.util.BasicProgressMonitor
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertTrue

/**
 * Stage C2: No-undeclared-relocation guard (AD-557-14).
 *
 * For all 12 corpus fixtures:
 *   1. Run mapper → ELK → snapshot ELK absolute bounds.
 *   2. Run the placement pipeline.
 *   3. Assert that every flow-node shape (excluding BoundaryEvent shapes — their placement is
 *      the AD-557-11 sanctioned decoration) whose bounds differ from its ELK bounds by more
 *      than POSITION_EPSILON has a ledger entry.
 *   4. Assert every ledger owner is one of the three declared moving conventions.
 *
 * This is the enforcement that AD-557-11 and AD-557-14 ordered: any new post-ELK node move
 * that isn't declared + paired + ledgered will be caught here before it can accumulate.
 */
class PlacementGuardTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun registerMetadata() {
            LayoutMetaDataService.getInstance().registerLayoutMetaDataProviders(LayeredMetaDataProvider())
        }

        private val DECLARED_OWNERS = setOf(
            "HandlerComponentAlignment",
            "SubprocessEndStraddle",
            "SubprocessSpineCentring",
        )

        private val EPS = BpmnPlacementPass.POSITION_EPSILON
    }

    @ParameterizedTest(name = "no-undeclared-relocation guard: {0}")
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
        ],
    )
    fun `every relocated flow-node is ledgered and every ledger owner is a declared convention`(fixture: String) {
        val input = load("layout-fixtures/$fixture.bpmn")
        val model = org.camunda.bpm.model.bpmn.Bpmn.readModelFromStream(
            java.io.ByteArrayInputStream(input.toByteArray(Charsets.UTF_8)),
        )
        // Remove existing DI so the mapper starts clean
        model.getModelElementsByType(org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnDiagram::class.java)
            .toList()
            .forEach { model.definitions.removeChildElement(it) }

        // Phase 1a: build skeleton
        val skeleton = BpmnToElkMapper.map(model)

        // Phase 1b: run ELK — snapshot absolute bounds BEFORE phase 2
        RecursiveGraphLayoutEngine().layout(skeleton.root, BasicProgressMonitor())
        val elkBounds = snapshot(skeleton)

        // Phase 2: run placement pipeline (keeps PlacementContext for guard inspection)
        val ctx = PlacementContext(
            model = model,
            skeleton = skeleton,
            shapes = mutableMapOf(),
            labels = mutableMapOf(),
            edges = mutableMapOf(),
            expanded = mutableSetOf(),
        )
        BpmnPlacementPass.run(ctx)

        // Collect IDs that are NOT subject to the guard:
        // - BoundaryEvent shapes: the AD-557-11 sanctioned decoration (not ledgered)
        // - TextAnnotation and Group: artifacts placed by ArtifactPlacement (not flow-nodes)
        val boundaryIds = model.getModelElementsByType(org.camunda.bpm.model.bpmn.instance.BoundaryEvent::class.java)
            .mapTo(mutableSetOf()) { it.id }
        val artifactIds = (
            model.getModelElementsByType(org.camunda.bpm.model.bpmn.instance.TextAnnotation::class.java)
                .map { it.id } +
                model.getModelElementsByType(org.camunda.bpm.model.bpmn.instance.Group::class.java)
                    .map { it.id }
            ).toSet()
        val excluded = boundaryIds + artifactIds

        // Guard: every flow-node shape that moved must have a ledger entry with a declared owner.
        // Filtered to only the IDs that are subject to the guard (non-excluded, present, and moved).
        elkBounds
            .filterKeys { it !in excluded }
            .forEach { (id, elkRect) ->
                val placed = ctx.shapes[id] ?: return@forEach
                val moved = kotlin.math.abs(placed.x - elkRect.x) > EPS ||
                    kotlin.math.abs(placed.y - elkRect.y) > EPS
                if (!moved) return@forEach
                val record = ctx.moves[id]
                assertTrue(
                    record != null,
                    "[$fixture] Flow node '$id' was relocated by phase 2 " +
                        "(ELK: ${elkRect.x},${elkRect.y} → placed: ${placed.x},${placed.y}) " +
                        "but has NO ledger entry. This violates the no-undeclared-relocation guard (AD-557-14).",
                )
                assertTrue(
                    record!!.owner in DECLARED_OWNERS,
                    "[$fixture] Flow node '$id' has a move ledger entry with undeclared owner '${record.owner}'. " +
                        "Declared owners: $DECLARED_OWNERS",
                )
            }

        // Guard: every ledger entry must have a declared owner
        for ((id, record) in ctx.moves) {
            assertTrue(
                record.owner in DECLARED_OWNERS,
                "[$fixture] Ledger entry for '$id' has undeclared owner '${record.owner}'. " +
                    "Declared owners: $DECLARED_OWNERS",
            )
        }
    }

    /**
     * Snapshots the absolute ELK positions of all nodes in the skeleton map.
     * Uses [BpmnPlacementPass.absolutePosition] to convert ELK-relative coordinates.
     */
    private fun snapshot(skeleton: BpmnToElkMapper.ElkSkeleton): Map<String, BpmnPlacementPass.Rect> {
        return skeleton.nodeMap.mapValues { (_, node) ->
            val (ax, ay) = BpmnPlacementPass.absolutePosition(node)
            BpmnPlacementPass.Rect(ax, ay, node.width, node.height)
        }
    }

    private fun load(resource: String): String = javaClass.classLoader.getResourceAsStream(resource)
        ?.use { it.readBytes().toString(Charsets.UTF_8) }
        ?: error("Resource not found: $resource")
}
