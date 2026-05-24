// Copyright 2026 The Project Contributors
// SPDX-License-Identifier: MIT

package dev.groknull.bpmner.tools.pklenumgen

import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    try {
        val options = CliOptions.parse(args.toList())
        PklEnumGenerator.generate(options.inputFiles, options.outDir)
    } catch (ex: IllegalArgumentException) {
        System.err.println(ex.message)
        exitProcess(2)
    } catch (ex: IllegalStateException) {
        System.err.println(ex.message)
        exitProcess(2)
    }
}

private data class CliOptions(
    val inputFiles: List<Path>,
    val outDir: Path,
) {
    companion object {
        fun parse(args: List<String>): CliOptions {
            if (args.isEmpty() || args.contains("--help")) {
                printUsage()
                exitProcess(0)
            }

            val inputFiles = mutableListOf<Path>()
            var outDir: Path? = null
            var index = 0
            while (index < args.size) {
                when (val arg = args[index]) {
                    "--input" -> {
                        index += 1
                        require(index < args.size) { "--input requires a file path." }
                        inputFiles.add(Path.of(args[index]))
                    }

                    "--out-dir" -> {
                        index += 1
                        require(index < args.size) { "--out-dir requires a directory path." }
                        outDir = Path.of(args[index])
                    }

                    else -> {
                        error("Unknown argument: $arg")
                    }
                }
                index += 1
            }

            return CliOptions(
                inputFiles = inputFiles,
                outDir = requireNotNull(outDir) { "--out-dir is required." },
            )
        }

        private fun printUsage() {
            println(
                """
                Usage: pkl-enum-gen --input <kotlin-source.kt> [--input <kotlin-source.kt> ...] --out-dir <directory>

                Generates NodeTypeName.pkl and NodeProperty.pkl from BPMNER Kotlin source.
                """.trimIndent(),
            )
        }
    }
}
