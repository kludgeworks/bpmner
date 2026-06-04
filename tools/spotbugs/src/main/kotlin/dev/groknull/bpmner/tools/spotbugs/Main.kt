// Copyright 2026 The Project Contributors
// SPDX-License-Identifier: MIT

package dev.groknull.bpmner.tools.spotbugs

import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val options = parseArgs(args)
    if (options == null) {
        System.err.println("Usage: spotbugs_sonar_converter --xml <spotbugs.xml> --out <sonar.json>")
        exitProcess(2)
    }

    val xml = options.xml.readText()
    options.out.parentFile?.mkdirs()
    options.out.writeText(SpotBugsSonarConverter.convert(xml))
}

private data class ConverterOptions(
    val xml: File,
    val out: File,
)

private fun parseArgs(args: Array<String>): ConverterOptions? {
    val values = args.toList().windowed(2, 2, partialWindows = true).associate {
        if (it.size == 2) it[0] to it[1] else it[0] to ""
    }
    val xml = values["--xml"]?.takeIf { it.isNotBlank() }?.let(::File)
    val out = values["--out"]?.takeIf { it.isNotBlank() }?.let(::File)
    return if (xml != null && out != null) ConverterOptions(xml, out) else null
}
