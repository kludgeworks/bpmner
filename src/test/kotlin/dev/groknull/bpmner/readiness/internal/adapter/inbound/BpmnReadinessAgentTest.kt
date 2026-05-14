package dev.groknull.bpmner.readiness.internal.adapter.inbound

import com.embabel.agent.test.unit.FakeOperationContext
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.ProcessInputAssessment
import dev.groknull.bpmner.core.ReadinessDimension
import dev.groknull.bpmner.core.ReadinessDimensionScore
import dev.groknull.bpmner.core.ReadinessVerdict
import dev.groknull.bpmner.readiness.internal.domain.BpmnReadinessPostChecker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BpmnReadinessAgentTest {
    @Test
    fun `assessReadiness returns post-checked assessment`() {
        val context = FakeOperationContext()
        context.expectResponse(assessment(ReadinessVerdict.READY, 92))
        val agent = BpmnReadinessAgent(BpmnConfig(), BpmnReadinessPostChecker())

        val result =
            agent.assessReadiness(
                BpmnRequest("Dashboard color choices only"),
                context,
            )

        assertEquals(ReadinessVerdict.NOT_A_PROCESS, result.verdict)
        assertTrue(result.overallScore < 40)
        assertEquals(1, context.llmInvocations.size)
    }

    @Test
    fun `prompt includes structured output and do not invent constraints`() {
        val prompt =
            BpmnReadinessPromptFactory(BpmnConfig().readiness)
                .prompt(BpmnRequest("When an order is submitted, review it, then ship it."))

        assertTrue(prompt.contains("Return only a structured ProcessInputAssessment object."))
        assertTrue(prompt.contains("Do not invent actors"))
        assertTrue(prompt.contains("Mark unsupported facts as missing"))
        assertTrue(prompt.contains(ReadinessDimension.START_TRIGGER.name))
    }

    private fun assessment(
        verdict: ReadinessVerdict,
        score: Int,
    ) = ProcessInputAssessment(
        verdict = verdict,
        overallScore = score,
        dimensions =
            ReadinessDimension.entries.map {
                ReadinessDimensionScore(
                    dimension = it,
                    score = score,
                    rationale = "Model score for ${it.name}.",
                )
            },
        rationale = "Model rationale.",
    )
}
