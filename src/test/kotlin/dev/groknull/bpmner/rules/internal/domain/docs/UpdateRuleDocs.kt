/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.docs

import dev.groknull.bpmner.rules.internal.domain.beans.BeanRuleRegistry
import dev.groknull.bpmner.rules.internal.domain.beans.bpmnerKotlinRuleContext
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * Regenerates golden documentation files from [RuleMetadata].
 *
 * Run with `bazel run //src/test:update_rule_docs` to update the golden files
 * in `src/test/resources/rule-docs/`.
 *
 * The output is written to the workspace root (`$BUILD_WORKSPACE_DIRECTORY`).
 */
object UpdateRuleDocs {

    @JvmStatic
    fun main(args: Array<String>) {
        val context = bpmnerKotlinRuleContext()
        try {
            val registry = context.getBean(BeanRuleRegistry::class.java)
            val rules = registry.activeRules() + registry.llmRuleSpecs()

            val rendered = RuleDocsRenderer.render(rules)
            val baseDir = Paths.get(System.getenv("BUILD_WORKSPACE_DIRECTORY"), "src", "test", "resources", "rule-docs")

            Files.createDirectories(baseDir)

            rendered.forEach { (filename, content) ->
                val outputPath = baseDir.resolve(filename)
                Files.writeString(outputPath, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                println("Wrote $filename")
            }

            println("Regenerated ${rendered.size} golden files in $baseDir")
        } finally {
            context.close()
        }
    }
}
