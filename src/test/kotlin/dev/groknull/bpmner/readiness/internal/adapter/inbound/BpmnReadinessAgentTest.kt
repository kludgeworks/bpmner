/*
 * Copyright (c) 2026 The Project Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package dev.groknull.bpmner.readiness.internal.adapter.inbound

import com.embabel.agent.test.unit.FakeOperationContext
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.ReadinessDimension
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessDimensionScore
import dev.groknull.bpmner.readiness.ReadinessVerdict
import org.mockito.Mockito.mock
import org.springframework.context.ApplicationEventPublisher
import kotlin.test.Test
import kotlin.test.assertEquals

class BpmnReadinessAgentTest {
    @Test
    fun `assessReadiness returns post-checked assessment`() {
        val context = FakeOperationContext()
        context.expectResponse(assessment(ReadinessVerdict.READY, 92))
        val eventPublisher = mock(ApplicationEventPublisher::class.java)
        val agent = BpmnReadinessAgent(BpmnConfig(), eventPublisher)

        val result =
            agent.assessReadiness(
                BpmnRequest("Dashboard color choices only"),
                context,
            )

        assertEquals(ReadinessVerdict.NEEDS_CLARIFICATION, result.verdict)
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
