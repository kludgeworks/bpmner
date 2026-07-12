/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Layer 4b: golden-file regression tests.
 *
 * For each corpus input fixture, asserts that [ElkBpmnLayouter] produces output that
 * is byte-for-byte identical to the committed golden file. ELK is deterministic (fixed
 * seed, stable insertion order), so this comparison is stable across runs.
 *
 * ## How golden files are created
 *
 * Golden files are NOT hand-crafted — they are the engine's own output, captured once
 * and approved by a human in bpmn-js. The workflow:
 *   1. Run `bazel run //src/test:generate_candidate_goldens` to produce candidate outputs.
 *   2. A human reviews each candidate in bpmn-js against the design references in
 *      `plans/557/reference/` and the placement convention checklist.
 *   3. On approval, the candidate is committed to `bpmn/elk-corpus/golden/` and this
 *      test is un-@Disabled for that fixture.
 *
 * Do NOT un-disable a test before human approval is recorded in the PR.
 * Do NOT fabricate golden files from the hand-crafted design references.
 */
@Disabled("Golden files pending human review — see plans/557/candidate-golden/")
class ElkGoldenLayoutTest {

    private val layouter = ElkBpmnLayouter()

    @Test
    fun `subprocess-flat output matches golden`() = assertMatchesGolden("subprocess-flat")

    @Test
    fun `subprocess-nested output matches golden`() = assertMatchesGolden("subprocess-nested")

    @Test
    fun `subprocess-branch output matches golden`() = assertMatchesGolden("subprocess-branch")

    @Test
    fun `boundary-timer-task output matches golden`() = assertMatchesGolden("boundary-timer-task")

    @Test
    fun `boundary-error-task output matches golden`() = assertMatchesGolden("boundary-error-task")

    @Test
    fun `boundary-multi output matches golden`() = assertMatchesGolden("boundary-multi")

    @Test
    fun `boundary-on-subprocess output matches golden`() = assertMatchesGolden("boundary-on-subprocess")

    private fun assertMatchesGolden(name: String) {
        val input = load("bpmn/elk-corpus/$name.bpmn")
        val golden = load("bpmn/elk-corpus/golden/$name.bpmn")
        val actual = layouter.layout(input)
        assertEquals(golden, actual, "Engine output for '$name' does not match committed golden file")
    }

    private fun load(resource: String): String = javaClass.classLoader.getResourceAsStream(resource)
        ?.use { it.readBytes().toString(Charsets.UTF_8) }
        ?: error("Resource not found: $resource")
}
