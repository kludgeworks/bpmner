package dev.groknull.bpmner.readiness.internal.adapter.inbound
import dev.groknull.bpmner.core.BpmnRequest



import com.embabel.agent.test.unit.FakeOperationContext
import kotlin.test.Test
import kotlin.test.assertEquals

class BpmnReadinessAgentTest {
    @Test
    fun `assessReadiness returns post-checked assessment`() {
        val context = FakeOperationContext()
        context.expectResponse(assessment(ReadinessVerdict.READY, 92))
        val agent = BpmnReadinessAgent(BpmnConfig())

        val result =
            agent.assessReadiness(
                BpmnRequest("Dashboard color choices only"),
                context,
            )

        assertEquals(ReadinessVerdict.NOT_A_PROCESS, result.verdict)
        assertEquals(1, context.llmInvocations.size)
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
