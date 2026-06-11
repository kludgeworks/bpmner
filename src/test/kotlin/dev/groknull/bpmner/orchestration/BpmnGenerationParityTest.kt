package dev.groknull.bpmner.orchestration

import com.embabel.agent.api.common.AgentPlatformTypedOps
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.Budget
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.groknull.bpmner.alignment.AlignmentFindings
import dev.groknull.bpmner.contract.internal.adapter.inbound.FlatProcessContract
import dev.groknull.bpmner.generation.BpmnResult
import dev.groknull.bpmner.generation.FlatBpmnDefinition
import dev.groknull.bpmner.prompt.PromptFixtures
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessVerdict
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.xmlunit.builder.DiffBuilder

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
class BpmnGenerationParityTest : EmbabelMockitoIntegrationTest() {
    @Autowired
    private lateinit var agentPlatform: AgentPlatform

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private inline fun <reified T> loadFixtureObject(name: String): T {
        val json = BpmnGenerationParityTest::class.java.getResource("/parity/$name")?.readText()
            ?: error("Fixture not found: /parity/$name")
        return objectMapper.readValue(json)
    }

    private fun loadFixture(name: String): String {
        return BpmnGenerationParityTest::class.java.getResource("/parity/$name")?.readText()
            ?: error("Fixture not found: /parity/$name")
    }

    private fun normalizeXml(xml: String): String {
        return xml.replace(Regex("""definitions_[0-9a-f\-]{36}"""), "definitions_ID")
            .replace(Regex("""conditionExpression_[0-9a-f\-]{36}"""), "conditionExpression_ID")
    }

    @Test
    fun generatesIdenticalToLegacyGoldenFixtures() {
        whenCreateObject({ true }, FlatProcessContract::class.java)
            .thenReturn(loadFixtureObject("canonicalContractFlat.json"))
        whenCreateObject({ true }, FlatBpmnDefinition::class.java)
            .thenReturn(loadFixtureObject("canonicalOutlineFlat.json"))
        whenCreateObject({ true }, AlignmentFindings::class.java)
            .thenReturn(loadFixtureObject("canonicalAlignment.json"))
        whenCreateObject({ true }, ProcessInputAssessment::class.java)
            .thenReturn(
                ProcessInputAssessment(
                    verdict = ReadinessVerdict.READY,
                    overallScore = 100,
                    dimensions = emptyList(),
                    missingAreas = emptyList(),
                    clarificationQuestions = emptyList(),
                    evidence = emptyList(),
                    rationale = "Mocked readiness",
                ),
            )

        val expectedBpmn = loadFixture("expected.bpmn")

        val result = AgentPlatformTypedOps(agentPlatform).transform(
            PromptFixtures.canonicalRequest,
            BpmnResult::class.java,
            ProcessOptions(budget = Budget(actions = 15), ephemeral = true),
        )

        val expectedNormalized = normalizeXml(expectedBpmn)
        val testNormalized = normalizeXml(result.xml!!)

        DiffBuilder.compare(expectedNormalized)
            .withTest(testNormalized)
            .ignoreWhitespace()
            .ignoreComments()
            .checkForSimilar()
            .build()
            .run { assertFalse(hasDifferences(), differences.toString()) }
    }
}
