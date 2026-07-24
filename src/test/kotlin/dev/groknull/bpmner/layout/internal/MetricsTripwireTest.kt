/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals

/**
 * AD-622-11 tier 2: per-fixture crossings/bends/overlaps counts, checked against a committed
 * baseline ([BASELINE_RESOURCE]). Unlike [ElkGoldenLayoutTest]'s exact-byte golden match, this
 * survives coordinate churn that doesn't change the shape of the diagram — but still fails on any
 * drift, so an intended change requires a deliberate re-bless (regenerate via
 * `bazel run //src/test:generate_candidate_goldens`, review the affected fixtures' rendered
 * output, then commit the updated baseline), never a code workaround.
 */
class MetricsTripwireTest {

    companion object {
        const val BASELINE_RESOURCE = "layout-fixtures/metrics-baseline.txt"

        @JvmStatic
        fun fixtures() = LAYOUT_CORPUS_FIXTURES
    }

    @ParameterizedTest(name = "metrics tripwire: {0}")
    @MethodSource("fixtures")
    fun `crossings, bends, and overlaps match the committed baseline`(fixture: String) {
        val input = javaClass.classLoader.getResourceAsStream("layout-fixtures/$fixture.bpmn")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Fixture not found: $fixture")
        val actual = ElkBpmnLayouter().apply { registerElkLayoutAlgorithm() }.layout(input)
        val doc = parse(actual)

        val baseline = loadBaseline()[fixture] ?: error("No baseline entry for '$fixture' in $BASELINE_RESOURCE")
        assertEquals(baseline, layoutMetrics(doc), "[$fixture] metrics drifted from the committed baseline")
    }

    private fun parse(xml: String): Document = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

    private fun loadBaseline(): Map<String, LayoutMetricsRow> = javaClass.classLoader
        .getResourceAsStream(BASELINE_RESOURCE)
        ?.use { it.readBytes().toString(Charsets.UTF_8) }
        ?.lines()
        ?.filter { it.isNotBlank() }
        ?.associate { line ->
            val (fixture, row) = parseBaselineLine(line)
            fixture to row
        }
        ?: error("Baseline resource not found: $BASELINE_RESOURCE")
}

/** One row of the committed baseline: a fixture's recorded crossings/bends/overlaps counts. */
internal data class LayoutMetricsRow(val crossings: Int, val bends: Int, val overlaps: Int)

/** Computes [LayoutMetricsRow] from a laid-out BPMN DI document. */
internal fun layoutMetrics(doc: Document): LayoutMetricsRow {
    val edges = extractEdges(doc)
    return LayoutMetricsRow(
        crossings = countCrossings(edges),
        bends = countBends(edges),
        overlaps = overlappingPairs(extractShapeRects(doc)).size,
    )
}

/** Formats one baseline row as `<fixture> <crossings> <bends> <overlaps>`. */
internal fun formatBaselineLine(fixture: String, row: LayoutMetricsRow): String =
    "$fixture ${row.crossings} ${row.bends} ${row.overlaps}"

internal fun parseBaselineLine(line: String): Pair<String, LayoutMetricsRow> {
    val parts = line.trim().split(Regex("\\s+"))
    require(parts.size == 4) { "Malformed metrics baseline line: '$line'" }
    return parts[0] to LayoutMetricsRow(parts[1].toInt(), parts[2].toInt(), parts[3].toInt())
}
