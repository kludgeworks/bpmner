/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain

import dev.groknull.bpmner.config.BpmnConfig
import dev.groknull.bpmner.config.BpmnRulesConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

internal class ConventionsLoaderTest {
    private val loader = ConventionsLoader()

    @Test
    fun `default modulepath bpmner pkl loads convention defaults`() {
        val config = loader.bpmnerLintConfig(BpmnConfig())

        assertThat(config.discouragedLeadingVerbs).containsExactly("handle", "manage", "process", "perform", "do")
        assertThat(config.elementTypeWords).containsExactly("activity", "process", "event")
        assertThat(config.allowedAcronyms).containsExactly("BPMN", "ACME", "SLA", "API", "IT")
        assertThat(config.technicalTokens).containsExactly("api", "svc", "tbl", "req", "resp", "tmp", "proc", "obj")
        assertThat(config.discouragedBpmnTypes).contains("bpmn:Transaction")
    }

    @Test
    fun `file uri override loads team conventions`(@TempDir tempDir: Path) {
        val pklFile = tempDir.resolve("team-bpmner.pkl")
        pklFile.toFile().writeText(
            """
            profile = "ignored-by-381"
            severityOverrides = new Mapping<String, String> {}
            discouragedLeadingVerbs = List("coordinate")
            elementTypeWords = List("step")
            allowedAcronyms = List("VIP")
            technicalTokens = List("impl")
            discouragedBpmnTypes = List("bpmn:Transaction")
            """.trimIndent(),
        )

        val config = loader.bpmnerLintConfig(BpmnConfig(rules = BpmnRulesConfig(configUri = pklFile.toUri().toString())))

        assertThat(config.discouragedLeadingVerbs).containsExactly("coordinate")
        assertThat(config.elementTypeWords).containsExactly("step")
        assertThat(config.allowedAcronyms).containsExactly("VIP")
        assertThat(config.technicalTokens).containsExactly("impl")
        assertThat(config.discouragedBpmnTypes).containsExactly("bpmn:Transaction")
    }

    @Test
    fun `invalid config uri fails startup loudly`() {
        val error = assertThrows(IllegalStateException::class.java) {
            loader.bpmnerLintConfig(BpmnConfig(rules = BpmnRulesConfig(configUri = "not a uri")))
        }

        assertThat(error.message).contains("Invalid BPMN lint config URI")
        assertThat(error.message).contains("not a uri")
    }

    @Test
    fun `configured override must be file uri`() {
        val error = assertThrows(IllegalStateException::class.java) {
            loader.bpmnerLintConfig(BpmnConfig(rules = BpmnRulesConfig(configUri = "modulepath:/linter/pkl/bpmner.pkl")))
        }

        assertThat(error.message).contains("must be a file: URI")
    }

    @Test
    fun `malformed pkl override fails startup loudly`(@TempDir tempDir: Path) {
        val pklFile = tempDir.resolve("broken-bpmner.pkl")
        pklFile.toFile().writeText(
            """
            profile = "recommended"
            severityOverrides = new Mapping<String, String> {}
            discouragedLeadingVerbs = List("coordinate")
            elementTypeWords = List("step")
            allowedAcronyms = List("VIP")
            technicalTokens = List("impl")
            discouragedBpmnTypes = List("bpmn:Transaction"
            """.trimIndent(),
        )

        val error = assertThrows(IllegalStateException::class.java) {
            loader.bpmnerLintConfig(BpmnConfig(rules = BpmnRulesConfig(configUri = pklFile.toUri().toString())))
        }

        assertThat(error.message).contains("Failed to evaluate BPMN lint config")
        assertThat(error.message).contains(pklFile.toUri().toString())
    }
}
