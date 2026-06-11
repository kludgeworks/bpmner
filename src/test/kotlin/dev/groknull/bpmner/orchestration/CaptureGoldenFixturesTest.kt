package dev.groknull.bpmner.orchestration

import com.embabel.agent.api.common.AgentPlatformTypedOps
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.Budget
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest
import com.fasterxml.jackson.databind.ObjectMapper
import dev.groknull.bpmner.generation.BpmnResult
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.io.File
import org.springframework.test.context.TestPropertySource

@TestPropertySource(
    properties = [
        "embabel.agent.platform.models.anthropic.api-key=\${ANTHROPIC_API_KEY}",
    ],
)
@SpringBootTest
class CaptureGoldenFixturesTest : EmbabelMockitoIntegrationTest() {
    @Autowired
    private lateinit var agentPlatform: AgentPlatform
    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun captureGoldenFixtures() {
        val input = "Generate a BPMN process that runs a credit check when a customer submits a credit application, approves it automatically if the score is at least 700, or routes it to a human underwriter for review otherwise."
        
        val result = AgentPlatformTypedOps(agentPlatform).transform(
            UserInput(input),
            BpmnResult::class.java,
            ProcessOptions(budget = Budget(actions = 15), ephemeral = true),
        )

        val outDir = File("src/test/resources/parity")
        outDir.mkdirs()

        result.contract?.let {
            File(outDir, "canonicalContractFlat.json").writeText(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(it))
        }
        result.definition?.let {
            File(outDir, "canonicalOutlineFlat.json").writeText(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(it))
        }
        result.alignmentReport?.let {
            File(outDir, "canonicalAlignment.json").writeText(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(it))
        }
        result.xml?.let {
            File(outDir, "expected.bpmn").writeText(it)
        }
    }
}
