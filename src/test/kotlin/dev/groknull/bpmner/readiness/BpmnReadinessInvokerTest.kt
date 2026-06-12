package dev.groknull.bpmner.readiness

import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest
import dev.groknull.bpmner.core.BpmnRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource

/**
 * Regression guard for the readiness sub-process recursion.
 *
 * `AgentPlatformBpmnReadinessInvoker.assess` used to run a whole-platform plan for
 * `ProcessInputAssessment`, which also matched the orchestrator's `assessReadiness` action — and that
 * action calls this invoker again, recursing without bound (it crashed every live smoke test). The
 * invoker now binds the sub-process to the readiness agent only, so a real `assess` call completes in a
 * single pass. This test exercises the real invoker offline (only the LLM is stubbed), exactly the call
 * the smoke tests make.
 */
@SpringBootTest
@ActiveProfiles("offline")
@TestPropertySource(
    properties = [
        "embabel.agent.platform.models.anthropic.api-key=mock-key",
        "embabel.agent.platform.models.gemini.api-key=mock-key",
        "embabel.agent.platform.models.mistralai.api-key=mock-key",
        "embabel.agent.platform.models.openai.api-key=mock-key",
    ],
)
class BpmnReadinessInvokerTest : EmbabelMockitoIntegrationTest() {
    @Autowired
    private lateinit var readinessInvoker: BpmnReadinessInvoker

    @Test
    fun `assess runs the readiness agent once without recursing`() {
        whenCreateObject({ true }, ProcessInputAssessment::class.java).thenReturn(
            ProcessInputAssessment(
                verdict = ReadinessVerdict.READY,
                overallScore = 100,
                dimensions = emptyList(),
                missingAreas = emptyList(),
                clarificationQuestions = emptyList(),
                evidence = emptyList(),
                rationale = "Mocked ready",
            ),
        )

        // Prose carries the deterministic readiness markers the post-checker scores: a start trigger
        // ("when"/"submitted"), several process verbs, a sequence marker ("then"), and end states.
        val prose = "When an order is submitted, it is reviewed, then approved and completed."
        val result = readinessInvoker.assess(BpmnRequest(processDescription = prose))

        assertEquals(ReadinessVerdict.READY, result.verdict)
    }
}
