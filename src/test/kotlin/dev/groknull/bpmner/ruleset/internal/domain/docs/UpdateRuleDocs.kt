/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset.internal.domain.docs

import dev.groknull.bpmner.ruleset.internal.domain.beans.BeanRuleRegistry
import dev.groknull.bpmner.ruleset.internal.domain.beans.bpmnerKotlinRuleContext
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * Regenerates the rule catalog from [RuleMetadata].
 *
 * Run with `bazel run //src/test:update_rule_docs` to update the golden files
 * in `rules.md`.
 *
 * The output is written to the workspace root (`$BUILD_WORKSPACE_DIRECTORY`).
 */
object UpdateRuleDocs {

    @JvmStatic
    fun main(args: Array<String>) {
        bpmnerKotlinRuleContext().use { context ->
            val registry = context.getBean(BeanRuleRegistry::class.java)
            val rules = registry.activeRules() + registry.llmRuleSpecs()

            val outputPath = Paths.get(System.getenv("BUILD_WORKSPACE_DIRECTORY"), "rules.md")
            Files.writeString(
                outputPath,
                RuleDocsRenderer.render(rules),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            println("Wrote $outputPath")
        }
    }
}
