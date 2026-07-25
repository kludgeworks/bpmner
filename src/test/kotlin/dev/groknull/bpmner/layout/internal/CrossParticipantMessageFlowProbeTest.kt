/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * P1 probe (622-Y, AD-622-19): does including cross-participant message flows as real ELK
 * edges (option B — `mapMessageFlows`'s guard at `:416` removed) perturb a participant's own
 * *internal* layout, or is the exclusion in the current docstring just unproven caution?
 *
 * **Verdict: perturbation is real — the falsifying half of AD-622-19's upgraded probe fires.**
 * Laid out `miwg-c2-four-pools.bpmn` with the guard removed and compared each participant's
 * children's bounds *relative to their own participant* against the guard-in-place baseline
 * (this test's committed assertion). Three of four participants (customer, warehouse,
 * carrier) were unchanged relative to their own bounds. `Participant_retailer` was not:
 *
 * | Node | Guard in place (rel. x, y) | Guard removed (rel. x, y) |
 * | --- | --- | --- |
 * | `Task_receive_order` | (224.0, 117.3) | (224.0, 59.2) |
 * | `SubProcess_fulfil` | (414.0, 54.8) | (419.0, 32.5) |
 *
 * `Task_receive_order` moved ~58px vertically relative to its own pool — a real internal
 * reordering from the cross-hierarchy edges' port dummies feeding the shared crossing
 * minimisation, not a uniform resize (every participant also grew taller, which is expected
 * and not perturbation by itself). This confirms PR #614's docstring rationale rather than
 * falsifying it: naive option B measurably perturbs `Participant_retailer`'s internal layout
 * for a route that would be routed once and never revisited.
 *
 * Per AD-622-19's own pre-recorded decision rule ("if internal layouts are perturbed
 * materially ... the corrected-C fallback is selected on evidence rather than on taste"),
 * this session's evidence selects the fallback. It does **not** settle whether the plan's
 * refinement — explicit `ElkPort`s with a declared `PORT_SIDE` on the participant (§2.2(b)),
 * rather than letting auto-generated hierarchical port dummies free-float — would avoid this
 * specific perturbation; that prototype is unbuilt and is the next probe, not this one.
 * Scoping corrected-C's extent, or building and re-probing §2.2(b), is the architect's call
 * (AD-622-13 already names corrected-C's extra scope as something to be named explicitly,
 * not absorbed into this session).
 *
 * The guard stays in place. This test pins the guard-in-place baseline as a regression
 * guard: if `Task_receive_order`'s position ever drifts from this baseline absent an
 * intentional mapper change, that is a signal this probe's premise has changed and P1
 * should be re-run.
 */
class CrossParticipantMessageFlowProbeTest {
    @Test
    fun `retailer participant's internal layout is stable with the cross-participant guard in place`() {
        val xml = javaClass.classLoader.getResourceAsStream("layout-fixtures/miwg-c2-four-pools.bpmn")
            ?.bufferedReader()?.readText() ?: error("fixture not found")
        val output = ElkBpmnLayouter().apply { registerElkLayoutAlgorithm() }.layout(xml)
        val doc = LayoutDiInspector.parse(output)

        val participant = LayoutDiInspector.shapeBounds(doc, "Participant_retailer")
        val task = LayoutDiInspector.shapeBounds(doc, "Task_receive_order")
        val relX = task["x"]!! - participant["x"]!!
        val relY = task["y"]!! - participant["y"]!!

        assertTrue(abs(relX - EXPECTED_REL_X) < TOLERANCE, "Task_receive_order x offset: expected ~$EXPECTED_REL_X, got $relX")
        assertTrue(abs(relY - EXPECTED_REL_Y) < TOLERANCE, "Task_receive_order y offset: expected ~$EXPECTED_REL_Y, got $relY")
    }

    private companion object {
        const val TOLERANCE = 0.1
        const val EXPECTED_REL_X = 224.03515625
        const val EXPECTED_REL_Y = 117.25
    }
}
