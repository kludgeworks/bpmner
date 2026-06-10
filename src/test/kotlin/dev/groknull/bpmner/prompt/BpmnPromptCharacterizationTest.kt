/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.prompt

import dev.groknull.bpmner.repair.internal.adapter.outbound.RepairFixtures
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertEquals

class BpmnPromptCharacterizationTest {

    @TestFactory
    fun `prompts match byte-for-byte golden string`(): List<DynamicTest> {
        val testDataDir = Paths.get("src/test/resources/prompt-goldens")

        fun check(key: String, content: String): DynamicTest = DynamicTest.dynamicTest("$key is byte-identical") {
            if (System.getenv("UPDATE_PROMPT_GOLDENS") == "true") {
                val workspaceDir = System.getenv("BUILD_WORKSPACE_DIRECTORY") ?: System.getProperty("user.dir")
                val testDataDir = Paths.get(workspaceDir, "src/test/resources/prompt-goldens")
                if (!Files.exists(testDataDir)) Files.createDirectories(testDataDir)
                Files.writeString(testDataDir.resolve("$key.txt"), content)
            }
            val expectedStream = javaClass.classLoader.getResourceAsStream("prompt-goldens/$key.txt")
                ?: error("Missing golden file: prompt-goldens/$key.txt")
            val expected = expectedStream.readAllBytes().decodeToString()
            assertEquals(expected, content, "Prompt $key must be byte-identical to golden baseline")
        }

        return listOf(
            check("contractPrompt", PromptFixtures.contract.render()),
            check("generationPrompt", PromptFixtures.generation.render()),
            check("alignmentPrompt", PromptFixtures.alignment.render()),
            check("readinessPrompt", PromptFixtures.readiness.render()),
            check("repairPatchPrompt", RepairFixtures.renderPatchFeedback()),
            check("repairFullPrompt", RepairFixtures.renderFullFeedback()),
            check(
                "draftBpmnRequestPrompt",
                PromptFixtures.gateFactory.draftBpmnRequestPrompt(
                    com.embabel.agent.domain.io.UserInput("test input"),
                ),
            ),
            check(
                "clarificationPrompt",
                PromptFixtures.gateFactory.clarificationPrompt(
                    PromptFixtures.canonicalAssessment,
                    0,
                    3,
                ),
            ),
        )
    }
}
