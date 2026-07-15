/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import java.io.File

/**
 * Generates the layout engine's output for each corpus input fixture and prints
 * each result to stdout, delimited by a header line.
 *
 * Run via: bazel run //src/test:generate_candidate_goldens
 *
 * The output is intended for human review in bpmn-js. Once a human approves an
 * output it becomes the committed golden file for that fixture.
 */
fun main() {
    val layouter = ElkBpmnLayouter()
    val fixtures = listOf(
        // Flat corpus (557-2)
        "representative-process",
        "explicit-cycle",
        "annotation-and-group",
        "long-labels",
        // Subprocess + boundary corpus (557-3)
        "subprocess-flat",
        "subprocess-nested",
        "subprocess-branch",
        "subprocess-loop",
        "boundary-timer-task",
        "boundary-error-task",
        "boundary-multi",
        "boundary-on-subprocess",
        "subprocess-no-start-cycle",
        "subprocess-sequential-sharing",
    )

    val workspaceDir = System.getenv("BUILD_WORKSPACE_DIRECTORY")
    if (workspaceDir != null) {
        val goldenDir = File(workspaceDir, "src/test/resources/layout-fixtures")
        goldenDir.mkdirs()
        for (name in fixtures) {
            val resource = "layout-fixtures/$name.bpmn"
            val input = object {}.javaClass.classLoader.getResourceAsStream(resource)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: error("Fixture not found: $resource")

            val output = layouter.layout(input)
            val file = File(goldenDir, "$name.expected.bpmn")
            file.writeText(output)
            println("Wrote expected file to ${file.absolutePath}")
        }
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
