/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset.internal.domain.docs

import dev.groknull.bpmner.ruleset.internal.domain.beans.BeanRuleRegistry
import dev.groknull.bpmner.ruleset.internal.domain.beans.bpmnerKotlinRuleContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystemNotFoundException
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Golden-file test for rule documentation rendering.
 *
 * Validates that the Kotlin-based docs renderer (`RuleDocsRenderer`) produces markdown
 * files identical to the committed golden files under
 * `src/test/resources/rule-docs/<id>.md` and `src/test/resources/rule-docs/README.md`.
 *
 * The golden source is the **bean metadata** (from `RuleMetadata`), NOT a byte-for-byte
 * copy of the old Pkl output. This discharges ADR-008's declaration that the bean
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

    private lateinit var registry: BeanRuleRegistry
    private lateinit var context: AnnotationConfigApplicationContext

    @BeforeAll
    fun setUp() {
        context = bpmnerKotlinRuleContext()
        registry = context.getBean(BeanRuleRegistry::class.java)
    }

    @AfterAll
    fun tearDown() {
        if (::context.isInitialized) {
            context.close()
        }
    }

    @Test
    @Suppress("LongMethod", "NestedBlockDepth")
    fun `rule docs match golden files`() {
        val rules = registry.activeRules() + registry.llmRuleSpecs()

        // Render all docs
        val rendered = RuleDocsRenderer.render(rules)

        // Load golden files from classpath resource directory
        val goldenFiles = mutableMapOf<String, String>()
        val ruleDocsUrl = javaClass.classLoader.getResource("rule-docs")
            ?: throw AssertionError("Resource directory 'rule-docs' not found on classpath")

        if (ruleDocsUrl.protocol == "file") {
            val ruleDocsDir = Paths.get(ruleDocsUrl.toURI())
            Files.list(ruleDocsDir).use { paths ->
                paths.forEach { path ->
                    val filename = path.fileName.toString()
                    if (filename.endsWith(".md")) {
                        val content = Files.readString(path, StandardCharsets.UTF_8)
                        goldenFiles[filename] = content
                    }
                }
            }
        } else if (ruleDocsUrl.protocol == "jar") {
            loadGoldenFilesFromJar(ruleDocsUrl, goldenFiles)
        } else {
            throw AssertionError("Expected 'rule-docs' resource to be a file system directory or jar, but was: $ruleDocsUrl")
        }

        // Assert set equality of filenames
        val renderedFilenames = rendered.keys.toSortedSet()
        val goldenFilenames = goldenFiles.keys.toSortedSet()
        assertThat(renderedFilenames)
            .describedAs("Golden files have orphan/missing entries vs rendered")
            .isEqualTo(goldenFilenames)

        // Assert content equality for each file
        for ((filename, expectedContent) in rendered) {
            val actualContent = goldenFiles[filename]
            assertThat(actualContent)
                .describedAs("Golden file for $filename")
                .isNotNull
            assertThat(actualContent)
                .describedAs("Content mismatch for $filename. Run `bazel run //src/test:update_rule_docs` to regenerate.")
                .isEqualTo(expectedContent)
        }
    }

    private fun loadGoldenFilesFromJar(
        ruleDocsUrl: java.net.URL,
        goldenFiles: MutableMap<String, String>,
    ) {
        val jarConnection = ruleDocsUrl.openConnection() as java.net.JarURLConnection
        val jarPathString = "jar:" + jarConnection.jarFileURL.toString()
        val entryName = jarConnection.entryName ?: ""
        val internalPath = if (entryName.startsWith("/")) entryName else "/$entryName"
        val uri = java.net.URI.create(jarPathString)
        var created = false
        val fs = try {
            java.nio.file.FileSystems.getFileSystem(uri)
        } catch (ignored: FileSystemNotFoundException) {
            created = true
            java.nio.file.FileSystems.newFileSystem(uri, emptyMap<String, Any>())
        }
        try {
            val ruleDocsDir = fs.getPath(internalPath)
            val mdPaths = Files.list(ruleDocsDir).use { paths ->
                paths.filter { it.fileName.toString().endsWith(".md") }.toList()
            }
            for (path in mdPaths) {
                val filename = path.fileName.toString()
                val content = Files.readString(path, StandardCharsets.UTF_8)
                goldenFiles[filename] = content
            }
        } finally {
            if (created) {
                fs.close()
            }
        }
    }
}
