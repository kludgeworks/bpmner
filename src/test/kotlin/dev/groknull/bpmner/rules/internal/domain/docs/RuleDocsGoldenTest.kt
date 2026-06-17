/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.docs

import dev.groknull.bpmner.rules.internal.domain.beans.BeanRuleRegistry
import dev.groknull.bpmner.rules.internal.domain.beans.bpmnerKotlinRuleContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Golden-file test for rule documentation rendering.
 *
 * Validates that the Kotlin-based docs renderer (`RuleDocsRenderer`) produces markdown
 * files identical to the committed golden files under
 * `src/test/resources/rule-docs/<id>.md` and `src/test/resources/rule-docs/README.md`.
 *
 * The golden source is the **bean metadata** (from `RuleMetadata`), NOT a byte-for-byte
 * copy of the old Pkl output. This discharges ADR-376-003's declaration that the bean
 * catalog is authoritative and the sole source of truth.
 *
 * On mismatch, the test failure message instructs developers to run the regenerate
 * binary `bazel run //src/test:update_rule_docs` to update the golden files.
 *
 * The test also verifies that the golden directory contains no orphan/missing files
 * by asserting set-equality between rendered filenames and golden filenames.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class RuleDocsGoldenTest {

    private lateinit var context: java.lang.AutoCloseable

    @BeforeAll
    fun setUp() {
        context = bpmnerKotlinRuleContext().use { it }
    }

    @AfterAll
    fun tearDown() {
        context.close()
    }

    @Test
    @Suppress("LongMethod")
    fun `rule docs match golden files`() {
        // Build context and collect all rules (both executable and LLM spec beans)
        val ctx = bpmnerKotlinRuleContext()
        try {
            val registry = ctx.getBean(BeanRuleRegistry::class.java)
            val rules = registry.activeRules() + registry.llmRuleSpecs()

            // Render all docs
            val rendered = RuleDocsRenderer.render(rules)

            // Load golden files from classpath
            // The resources are included in the test JAR via resources = glob(["resources/**"])
            // so they appear as "rule-docs/act-activity-label-capitalization.md" etc.
            val goldenFiles = mutableMapOf<String, String>()
            val goldenRuleIds = rules.map { it.metadata.id }

            for (ruleId in goldenRuleIds) {
                val resourceName = "rule-docs/$ruleId.md"
                val stream: InputStream? = javaClass.classLoader.getResourceAsStream(resourceName)
                if (stream != null) {
                    val content = stream.reader(StandardCharsets.UTF_8).use { it.readText() }
                    goldenFiles["$ruleId.md"] = content
                    stream.close()
                }
            }

            // Also load README.md
            val readmeStream: InputStream? = javaClass.classLoader.getResourceAsStream("rule-docs/README.md")
            if (readmeStream != null) {
                val content = readmeStream.reader(StandardCharsets.UTF_8).use { it.readText() }
                goldenFiles["README.md"] = content
                readmeStream.close()
            }

            // Assert set equality of filenames
            val renderedFilenames = rendered.keys.toSortedSet()
            val goldenFilenames = goldenFiles.keys.toSortedSet()
            assertThat(renderedFilenames).isEqualTo(goldenFilenames)
                .describedAs("Golden files have orphan/missing entries vs rendered")

            // Assert content equality for each file
            for ((filename, expectedContent) in rendered) {
                val actualContent = goldenFiles[filename]
                assertThat(actualContent)
                    .describedAs("Golden file for $filename")
                    .isNotNull
                assertThat(actualContent).isEqualTo(expectedContent)
                    .describedAs("Content mismatch for $filename. Run `bazel run //src/test:update_rule_docs` to regenerate.")
            }
        } finally {
            ctx.close()
        }
    }
}
