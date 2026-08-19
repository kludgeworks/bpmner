/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.smoke

import com.embabel.agent.api.common.AgentPlatformTypedOps
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.Budget
import com.embabel.agent.core.ProcessOptions
import dev.groknull.bpmner.authoring.BpmnGenerationStatus
import dev.groknull.bpmner.authoring.BpmnResult
import dev.groknull.bpmner.bpmn.BpmnRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText

@Tag("manual")
@Tag("live-llm")
@EnabledForLiveLlmProfile
@ExtendWith(SmokeTestSummaryExtension::class)
@SpringBootTest
@TestPropertySource(
    properties = [
        "embabel.agent.platform.models.anthropic.api-key=\${ANTHROPIC_API_KEY:}",
        "embabel.agent.platform.models.gemini.api-key=\${GEMINI_API_KEY:}",
        "embabel.agent.platform.models.mistralai.api-key=\${MISTRAL_API_KEY:}",
        "embabel.agent.platform.models.deepseek.api-key=\${DEEPSEEK_API_KEY:}",
        "spring.shell.interactive.enabled=false",
        "spring.shell.noninteractive.enabled=false",
        "embabel.agent.shell.interactive.enabled=false",
        "bpmner.rules.severity-overrides.evt-event-state-name=off",
        "bpmner.rules.severity-overrides.evt-event-state-pattern=off",
    ],
)
class LiveLlmFullPipelineSmokeTest {
    @Autowired
    private lateinit var agentPlatform: AgentPlatform

    @Test
    fun `pipeline produces a valid BPMN from employee-onboarding prose`(
        @TempDir tempDir: Path,
    ) {
        val prose = loadSample("employee-onboarding.prose.md")
        val outputFile = tempDir.resolve("employee-onboarding.bpmn")

        val result = try {
            AgentPlatformTypedOps(agentPlatform)
                .transform(
                    BpmnRequest(
                        processDescription = prose,
                        outputFile = outputFile.toString(),
                    ),
                    BpmnResult::class.java,
                    ProcessOptions(
                        budget = Budget(actions = 100),
                        ephemeral = false,
                        listeners = listOf(SuiteCostCapturer),
                    ),
                )
        } catch (failure: Exception) {
            Assumptions.assumeFalse(
                isLiveProviderQuotaOrSizeLimit(failure),
                "Live LLM provider quota or request-size limit hit; skipping. Cause: ${failure.message}",
            )
            throw failure
        }

        assertEquals(BpmnGenerationStatus.GENERATED, result.status)
        val xml = requireNotNull(result.xml) { "Expected BPMN XML for GENERATED status" }
        assertTrue(xml.contains("<process"), "Expected XML to contain <process tag")
        assertEquals(outputFile.toString(), result.outputFile)
        assertEquals(xml, outputFile.readText())
    }

    /**
     * Acceptance harness for boundary-event fidelity through the full pipeline: this contract's
     * two timer boundary events must survive extraction and outline generation into the rendered
     * BPMN. Offline gates cannot decide this; only a live run can.
     */
    @Test
    fun `pipeline clears outline generation on the first attempt with both timer boundary events from payment-processing prose`(
        @TempDir tempDir: Path,
    ) {
        val prose = loadSample("payment-processing.prose.md")
        val outputFile = tempDir.resolve("payment-processing.bpmn")

        val result = try {
            AgentPlatformTypedOps(agentPlatform)
                .transform(
                    BpmnRequest(
                        processDescription = prose,
                        outputFile = outputFile.toString(),
                    ),
                    BpmnResult::class.java,
                    ProcessOptions(
                        budget = Budget(actions = 100),
                        ephemeral = false,
                        listeners = listOf(SuiteCostCapturer),
                    ),
                )
        } catch (failure: Exception) {
            Assumptions.assumeFalse(
                isLiveProviderQuotaOrSizeLimit(failure),
                "Live LLM provider quota or request-size limit hit; skipping. Cause: ${failure.message}",
            )
            throw failure
        }

        assertEquals(
            BpmnGenerationStatus.GENERATED,
            result.status,
            "Expected createOutline to clear on the first attempt; detail: ${result.failureDetail}",
        )
        val xml = requireNotNull(result.xml) { "Expected BPMN XML for GENERATED status" }
        val timerBoundaryEventCount = Regex("<bpmn:boundaryEvent\\b[\\s\\S]*?bpmn:timerEventDefinition").findAll(xml).count()
        assertTrue(
            timerBoundaryEventCount >= 2,
            "Expected both contract timer boundary events (60s decline timeout, 30d lost-cart) in the " +
                "generated BPMN; found $timerBoundaryEventCount. xml:\n$xml",
        )
        val unreachable = result.alignmentReport?.bpmnSummary?.unreachableElementIds.orEmpty()
        assertTrue(unreachable.isEmpty(), "Expected no orphaned activities; unreachable: $unreachable")
    }

    private fun loadSample(name: String): String {
        val testSrcDir = System.getenv("TEST_SRCDIR")
        val testWorkspace = System.getenv("TEST_WORKSPACE")
        Assumptions.assumeTrue(
            testSrcDir != null && testWorkspace != null,
            "Bazel runfiles env (TEST_SRCDIR / TEST_WORKSPACE) not present; skipping. Run via `bazel test`.",
        )
        return Paths.get(testSrcDir, testWorkspace, "samples", name).readText()
    }

    private fun isLiveProviderQuotaOrSizeLimit(failure: Throwable): Boolean {
        return generateSequence(failure) { it.cause }.any { cause ->
            val message = cause.message.orEmpty()
            val normalized = message.lowercase()
            "429" in message ||
                "too many requests" in normalized ||
                "rate limit" in normalized ||
                "rate_limit_error" in normalized ||
                "overloaded_error" in normalized ||
                "413" in message ||
                "tokens_limit_reached" in message ||
                "context_length_exceeded" in normalized ||
                "credit balance" in normalized ||
                // A generic 400 invalid_request_error is deliberately NOT treated as skippable —
                // a malformed request or a genuine prompt/schema regression surfaces the same
                // code, and this smoke test is the harness that must catch exactly that.
                // Only the two explicit, non-quota-adjacent request-size phrasings some providers
                // use for "your request was too big" are recognized here.
                "request too large" in normalized ||
                "maximum context length" in normalized
        }
    }
}
