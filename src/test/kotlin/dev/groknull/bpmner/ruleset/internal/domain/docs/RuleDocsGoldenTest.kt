/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset.internal.domain.docs

import com.google.devtools.build.runfiles.Runfiles
import dev.groknull.bpmner.ruleset.internal.domain.beans.BeanRuleRegistry
import dev.groknull.bpmner.ruleset.internal.domain.beans.bpmnerKotlinRuleContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.nio.file.Files

/**
 * Golden-file test for rule documentation rendering.
 *
 * Validates that the Kotlin-based docs renderer (`RuleDocsRenderer`) produces the committed
 * root `rules.md` catalog from live bean metadata.
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
    fun `rule catalog matches golden file`() {
        val rules = registry.activeRules() + registry.llmRuleSpecs()
        val rendered = RuleDocsRenderer.render(rules)
        val runfiles = Runfiles.preload().withSourceRepository("")
        val catalog = runfiles.rlocation("bpmner/rules.md")
            ?: runfiles.rlocation("_main/rules.md")
            ?: runfiles.rlocation("rules.md")
            ?: throw AssertionError("Could not resolve rules.md via runfiles")

        assertThat(Files.readString(java.nio.file.Path.of(catalog)))
            .describedAs("Content mismatch for rules.md. Run `bazel run //src/test:update_rule_docs` to regenerate.")
            .isEqualTo(rendered)
    }
}
