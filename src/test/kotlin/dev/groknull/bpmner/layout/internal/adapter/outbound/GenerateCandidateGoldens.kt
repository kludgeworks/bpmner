/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

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
        "subprocess-flat",
        "subprocess-nested",
        "subprocess-branch",
        "subprocess-loop",
        "boundary-timer-task",
        "boundary-error-task",
        "boundary-multi",
        "boundary-on-subprocess",
    )

    for (name in fixtures) {
        val resource = "bpmn/elk-corpus/$name.bpmn"
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
