/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Generates the layout engine's output for each corpus input fixture and prints
 * each result to stdout, delimited by a header line. Also regenerates the AD-622-11 tier-2
 * metrics baseline ([MetricsTripwireTest.BASELINE_RESOURCE]).
 *
 * Run via: bazel run //src/test:generate_candidate_goldens
 *
 * The output is intended for human review in bpmn-js. Once a human approves an
 * output it becomes the committed golden file for that fixture.
 */
fun main() {
    val layouter = ElkBpmnLayouter(clock = LAYOUT_GOLDEN_CLOCK).apply { registerElkLayoutAlgorithm() }
    val docBuilder = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
    val fixtures = LAYOUT_CORPUS_FIXTURES

    val workspaceDir = System.getenv("BUILD_WORKSPACE_DIRECTORY")
    if (workspaceDir != null) {
        val goldenDir = File(workspaceDir, "src/test/resources/layout-fixtures")
        goldenDir.mkdirs()
        val baselineLines = mutableListOf<String>()
        for (name in fixtures) {
            val resource = "layout-fixtures/$name.bpmn"
            val input = object {}.javaClass.classLoader.getResourceAsStream(resource)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: error("Fixture not found: $resource")

            val output = layouter.layout(input)
            val file = File(goldenDir, "$name.expected.bpmn")
            file.writeText(output)
            println("Wrote expected file to ${file.absolutePath}")

            val doc = docBuilder.newDocumentBuilder().parse(ByteArrayInputStream(output.toByteArray(Charsets.UTF_8)))
            baselineLines += formatBaselineLine(name, layoutMetrics(doc))
        }
        val baselineFile = File(goldenDir, "metrics-baseline.txt")
        baselineFile.writeText(baselineLines.sorted().joinToString("\n", postfix = "\n"))
        println("Wrote metrics baseline to ${baselineFile.absolutePath}")
    } else {
        for (name in fixtures) {
            val resource = "layout-fixtures/$name.bpmn"
            val input = object {}.javaClass.classLoader.getResourceAsStream(resource)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: error("Fixture not found: $resource")

            val output = layouter.layout(input)

            println("=== BEGIN $name.bpmn ===")
            println(output)
            println("=== END $name.bpmn ===")
            println()
        }
    }
}
