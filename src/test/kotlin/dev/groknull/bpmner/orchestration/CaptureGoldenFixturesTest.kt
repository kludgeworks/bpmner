package dev.groknull.bpmner.orchestration

import com.embabel.agent.api.common.AgentPlatformTypedOps
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.Budget
import com.embabel.agent.core.Goal
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.spi.common.Constants
import com.fasterxml.jackson.databind.ObjectMapper
import dev.groknull.bpmner.contract.internal.adapter.inbound.FlatProcessContract
import dev.groknull.bpmner.generation.FlatBpmnDefinition
import dev.groknull.bpmner.generation.BpmnResult
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessVerdict
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
class CaptureGoldenFixturesTest {
    @Autowired
    private lateinit var agentPlatform: AgentPlatform
    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun captureGoldenFixtures() {
        val input = "Generate a BPMN process that runs a credit check when a customer submits a credit application, approves it automatically if the score is at least 700, or routes it to a human underwriter for review otherwise."
        
        val request = BpmnRequest(processDescription = input)
        val assessment = ProcessInputAssessment(
            verdict = ReadinessVerdict.READY,
            overallScore = 100,
            dimensions = emptyList(),
            evidence = emptyList(),
            rationale = "Ready"
        )
        val goalAgent =
            agentPlatform
                .createAgent(
                    name = "goal-BpmnResult",
                    provider = Constants.EMBABEL_PROVIDER,
                    description = "Goal agent",
                ).withSingleGoal(
                    Goal(
                        name = "create-BpmnResult",
                        description = "Create BpmnResult",
                        satisfiedBy = BpmnResult::class.java,
                    ),
                )

        val process = agentPlatform.createAgentProcessFrom(
            goalAgent,
            ProcessOptions(budget = Budget(actions = 15), ephemeral = false),
            request,
            assessment
        ).run()

        val result = process.last(BpmnResult::class.java)!!

        val outDirEnv = System.getenv("TEST_UNDECLARED_OUTPUTS_DIR") ?: "src/test/resources/parity"
        val outDir = File(outDirEnv)
        outDir.mkdirs()

        process.last(FlatProcessContract::class.java)?.let {
            File(outDir, "canonicalContractFlat.json").writeText(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(it))
        }
        process.last(FlatBpmnDefinition::class.java)?.let {
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
