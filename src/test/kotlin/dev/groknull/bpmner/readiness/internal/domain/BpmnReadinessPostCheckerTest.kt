package dev.groknull.bpmner.readiness.internal.domain
import dev.groknull.bpmner.core.BpmnRequest



import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BpmnReadinessPostCheckerTest {
    private val checker = BpmnReadinessPostChecker()

    @Test
    fun `strong process text returns READY`() {
        val result =
            checker.apply(
                BpmnRequest(
                    "When an order is submitted, the clerk reviews it, then approves it, " +
                        "then ships it, and finally the order is completed.",
                ),
                assessment(ReadinessVerdict.READY, 88),
            )

        assertEquals(ReadinessVerdict.READY, result.verdict)
        assertEquals(88, result.overallScore)
        assertEquals(ReadinessDimension.entries.toSet(), result.dimensions.map { it.dimension }.toSet())
    }

    @Test
    fun `weak process text returns NEEDS_CLARIFICATION`() {
        val result =
            checker.apply(
                BpmnRequest("Review the request"),
                assessment(ReadinessVerdict.READY, 90),
            )

        assertEquals(ReadinessVerdict.NEEDS_CLARIFICATION, result.verdict)
        assertTrue(result.overallScore < 75)
        assertTrue(MissingProcessArea.START_TRIGGER in result.missingAreas)
        assertTrue(MissingProcessArea.END_STATE in result.missingAreas)
        assertTrue(MissingProcessArea.ACTIVITY_SEQUENCE in result.missingAreas)
        assertTrue(result.clarificationQuestions.isNotEmpty())
    }

    @Test
    fun `custom thresholds cap deterministic failures below ready threshold`() {
        val result =
            BpmnReadinessPostChecker(BpmnReadinessConfig(readyThreshold = 50, clarificationThreshold = 20)).apply(
                BpmnRequest("Review the request"),
                assessment(ReadinessVerdict.READY, 90),
            )

        assertEquals(ReadinessVerdict.NEEDS_CLARIFICATION, result.verdict)
        assertEquals(49, result.overallScore)
    }

    @Test
    fun `non-process text returns NOT_A_PROCESS`() {
        val result =
            checker.apply(
                BpmnRequest("The dashboard should be blue and have three tabs."),
                assessment(ReadinessVerdict.READY, 91),
            )

        assertEquals(ReadinessVerdict.NOT_A_PROCESS, result.verdict)
        assertTrue(result.overallScore < 40)
        assertTrue(MissingProcessArea.BPMN_PROCESS_SUITABILITY in result.missingAreas)
    }

    @Test
    fun `blank process text returns NOT_A_PROCESS without clarification questions`() {
        val result =
            checker.apply(
                BpmnRequest(""),
                assessment(
                    verdict = ReadinessVerdict.NEEDS_CLARIFICATION,
                    score = 60,
                    questions =
                        listOf(
                            ClarificationQuestion(
                                id = "q1",
                                questionText = "What starts the process?",
                                relatedMissingAreas = listOf(MissingProcessArea.START_TRIGGER),
                                relatedDimensions = listOf(ReadinessDimension.START_TRIGGER),
                            ),
                        ),
                ),
            )

        assertEquals(ReadinessVerdict.NOT_A_PROCESS, result.verdict)
        assertTrue(result.clarificationQuestions.isEmpty())
    }

    @Test
    fun `missing trigger end state and sequence lower dimensions`() {
        val result =
            checker.apply(
                BpmnRequest("The clerk receives the request and validates it"),
                assessment(ReadinessVerdict.READY, 90),
            )

        assertEquals(ReadinessVerdict.NEEDS_CLARIFICATION, result.verdict)
        assertDimensionLowered(result, ReadinessDimension.END_STATES, MissingProcessArea.END_STATE)
        assertDimensionLowered(result, ReadinessDimension.SEQUENCE_ORDER, MissingProcessArea.ACTIVITY_SEQUENCE)
    }

    @Test
    fun `fewer than minimum activities lowers readiness`() {
        val result =
            BpmnReadinessPostChecker(BpmnReadinessConfig(minimumActivityCount = 3)).apply(
                BpmnRequest("When an application arrives, review it, then the application is completed."),
                assessment(ReadinessVerdict.READY, 85),
            )

        assertEquals(ReadinessVerdict.NEEDS_CLARIFICATION, result.verdict)
        assertDimensionLowered(result, ReadinessDimension.ACTIVITIES, MissingProcessArea.ACTIVITY_SEQUENCE)
    }

    @Test
    fun `clarification questions are capped and tied to missing dimensions`() {
        val result =
            BpmnReadinessPostChecker(BpmnReadinessConfig(maxClarificationQuestions = 2)).apply(
                BpmnRequest("Review the request"),
                assessment(
                    verdict = ReadinessVerdict.NEEDS_CLARIFICATION,
                    score = 70,
                    questions =
                        List(6) { index ->
                            ClarificationQuestion(
                                id = "q$index",
                                questionText = "Question $index?",
                            )
                        },
                ),
            )

        assertEquals(2, result.clarificationQuestions.size)
        assertTrue(result.clarificationQuestions.all { it.relatedMissingAreas.isNotEmpty() })
        assertTrue(result.clarificationQuestions.all { it.relatedDimensions.isNotEmpty() })
    }

    private fun assertDimensionLowered(
        assessment: ProcessInputAssessment,
        dimension: ReadinessDimension,
        area: MissingProcessArea,
    ) {
        val score = assessment.dimensions.single { it.dimension == dimension }
        assertTrue(score.score <= 40, "$dimension should be lowered")
        assertTrue(area in score.missingAreas)
    }

    private fun assessment(
        verdict: ReadinessVerdict,
        score: Int,
        questions: List<ClarificationQuestion> = emptyList(),
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
        clarificationQuestions = questions,
        rationale = "Model rationale.",
    )
}
