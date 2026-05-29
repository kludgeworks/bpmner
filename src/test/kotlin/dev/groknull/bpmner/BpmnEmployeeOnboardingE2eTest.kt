/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner

import com.embabel.agent.api.common.AgentPlatformTypedOps
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.Budget
import com.embabel.agent.core.ProcessOptions
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.generation.BpmnGenerationStatus
import dev.groknull.bpmner.generation.BpmnResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText

/**
 * End-to-end smoke test: drives a real prose sample through the full pipeline against the
 * GitHub Models LLM endpoint. Skipped locally without `GITHUB_TOKEN`; in CI it runs only when
 * the `e2e` Bazel tag is selected (see `.bazelrc`).
 *
 * The Bazel tag is the primary gate; the env-var guard is belt-and-braces for accidental runs
 * outside Bazel (IDE, raw gradle, etc.).
 */
@SpringBootTest
@ActiveProfiles("github")
@TestPropertySource(
    properties = [
        "spring.shell.interactive.enabled=false",
        "spring.shell.noninteractive.enabled=false",
        "embabel.agent.shell.interactive.enabled=false",
    ],
)
@EnabledIfEnvironmentVariable(named = "GITHUB_TOKEN", matches = ".+")
class BpmnEmployeeOnboardingE2eTest {
    @Autowired
    private lateinit var agentPlatform: AgentPlatform

    @Test
    fun `pipeline produces a valid BPMN from employee-onboarding prose`(
        @TempDir tempDir: Path,
    ) {
        val prose = loadSample("employee-onboarding.prose.md")
        val outputFile = tempDir.resolve("employee-onboarding.bpmn")

        val result =
            runCatching {
                AgentPlatformTypedOps(agentPlatform)
                    .transform(
                        BpmnRequest(
                            processDescription = prose,
                            outputFile = outputFile.toString(),
                        ),
                        BpmnResult::class.java,
                        ProcessOptions(budget = Budget(actions = 100), ephemeral = true),
                    )
            }.onFailure { failure ->
                Assumptions.assumeTrue(
                    !isRateLimited(failure),
                    "GitHub Models rate limit hit; skipping. Cause: ${failure.message}",
                )
                throw failure
            }.getOrThrow()

        assertEquals(BpmnGenerationStatus.GENERATED, result.status)
        assertNotNull(result.xml, "Expected BPMN XML for GENERATED status")
        assertTrue(result.xml!!.contains("<process"), "Expected XML to contain <process tag")
        assertEquals(outputFile.toString(), result.outputFile)
        assertEquals(result.xml, outputFile.readText())
    }

    private fun loadSample(name: String): String {
        val testSrcDir = System.getenv("TEST_SRCDIR") ?: error("TEST_SRCDIR not set (run via Bazel)")
        val testWorkspace = System.getenv("TEST_WORKSPACE") ?: error("TEST_WORKSPACE not set (run via Bazel)")
        return Paths.get(testSrcDir, testWorkspace, "samples", name).readText()
    }

    private fun isRateLimited(failure: Throwable): Boolean {
        return generateSequence(failure as Throwable?) { it.cause }.any { cause ->
            val message = cause.message.orEmpty()
            "429" in message || "Too Many Requests" in message || "rate limit" in message.lowercase()
        }
    }
}
