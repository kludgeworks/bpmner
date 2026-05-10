package dev.groknull.bpmner.core

import com.embabel.agent.api.common.Actor
import com.embabel.agent.prompt.persona.Persona
import com.embabel.common.ai.model.LlmOptions
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("bpmner")
data class BpmnConfig(
    val maxAttempts: Int = 5,
    val generator: Actor<Persona> = DEFAULT_GENERATOR,
    val repairer: Actor<Persona> = DEFAULT_REPAIRER,
    val logging: BpmnLoggingConfig = BpmnLoggingConfig(),
    val repair: BpmnRepairConfig = BpmnRepairConfig(),
) {
    companion object {
        val DEFAULT_GENERATOR = Actor(
            persona = Persona(
                name = "BPMN Designer",
                persona = "You are an expert BPMN 2.0 process modeller",
                objective = "Create a valid, well-structured BPMN process definition from a business description",
                voice = "precise and thorough",
            ),
            llm = LlmOptions.withLlmForRole("generator"),
        )
        val DEFAULT_REPAIRER = Actor(
            persona = Persona(
                name = "BPMN Repair Specialist",
                persona = "You are a strict BPMN 2.0 validator and repair expert",
                objective = "Fix every validation error in the BPMN definition and return the complete corrected object",
                voice = "concise and exact",
            ),
            llm = LlmOptions.withLlmForRole("repairer"),
        )
    }
}

data class BpmnLoggingConfig(
    val dumpArtifacts: Boolean = false,
    val artifactPreviewLength: Int = 8000,
)

data class BpmnRepairConfig(
    val abbreviations: Map<String, String> = emptyMap(),
)
