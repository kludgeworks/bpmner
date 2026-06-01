/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.smoke

import dev.groknull.bpmner.generation.BpmnGenerationInput
import dev.groknull.bpmner.generation.BpmnGenerationStatus
import dev.groknull.bpmner.generation.BpmnGenerationUseCase
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Tag("manual")
@Tag("live-llm")
@Tag("e2e")
@EnabledIfEnvironmentVariable(named = "GITHUB_TOKEN", matches = ".+")
@ExtendWith(SmokeTestSummaryExtension::class)
@SpringBootTest
@ActiveProfiles("github")
@Timeout(5, unit = TimeUnit.MINUTES)
@TestPropertySource(
    properties = [
        "embabel.agent.platform.models.github.api-key=\${GITHUB_TOKEN:}",
        "spring.shell.interactive.enabled=false",
        "spring.shell.noninteractive.enabled=false",
    ],
)
class GitHubModelsFullPipelineE2eSmokeTest {

    @Autowired
    private lateinit var generationUseCase: BpmnGenerationUseCase

    @Test
    fun `github models can generate a complete bpmn file`(
        @org.junit.jupiter.api.io.TempDir tempDir: Path,
    ) {
        val outputFile = tempDir.resolve("github-models-smoke.bpmn")

        val result = generationUseCase.generate(
            BpmnGenerationInput(
                processDescription = """
                    When a customer submits a support request, the system records the request.
                    A support agent reviews the request, the system sends a response to the customer,
                    and the process ends after the response is sent.
                """.trimIndent(),
                outputFile = outputFile.toString(),
                styleGuideContent = "Use sentence case for task and event names.",
            ),
        )

        assertEquals(BpmnGenerationStatus.GENERATED, result.status)
        val xml = assertNotNull(result.xml, "Expected GitHub Models to produce BPMN XML")
        assertTrue(outputFile.exists(), "Expected BPMN output file to be written")
        assertEquals(xml, outputFile.readText())
        assertTrue(
            xml.contains("<bpmn:process") || xml.contains("<process"),
            "Expected BPMN XML to contain a process element",
        )
    }
}
