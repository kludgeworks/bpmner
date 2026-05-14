package dev.groknull.bpmner.core

import com.embabel.agent.api.common.Actor
import com.embabel.agent.prompt.persona.Persona
import com.embabel.common.ai.model.LlmOptions
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("bpmner")
data class BpmnConfig(
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    val generator: Actor<Persona> = DEFAULT_GENERATOR,
    val repairer: Actor<Persona> = DEFAULT_REPAIRER,
    val contractExtractor: Actor<Persona> = DEFAULT_CONTRACT_EXTRACTOR,
    val contract: BpmnContractConfig = BpmnContractConfig(),
    val logging: BpmnLoggingConfig = BpmnLoggingConfig(),
    val repair: BpmnRepairConfig = BpmnRepairConfig(),
) {
    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 5

        val DEFAULT_GENERATOR =
            Actor(
                persona =
                    Persona(
                        name = "BPMN Designer",
                        persona = "You are an expert BPMN 2.0 process modeller",
                        objective =
                            "Create a valid, well-structured BPMN process definition from a business description",
                        voice = "precise and thorough",
                    ),
                llm = LlmOptions.withLlmForRole("generator"),
            )
        val DEFAULT_REPAIRER =
            Actor(
                persona =
                    Persona(
                        name = "BPMN Repair Specialist",
                        persona = "You are a strict BPMN 2.0 validator and repair expert",
                        objective =
                            "Fix every validation error in the BPMN definition" +
                                " and return the complete corrected object",
                        voice = "concise and exact",
                    ),
                llm = LlmOptions.withLlmForRole("repairer"),
            )
        val DEFAULT_CONTRACT_EXTRACTOR =
            Actor(
                persona =
                    Persona(
                        name = "Process Contract Extractor",
                        persona =
                            "You are a conservative business analyst who extracts source-grounded process contracts",
                        objective =
                            "Produce a typed ProcessContract whose every element is traceable to the source" +
                                " input, an assessment evidence id, a clarification answer, or an explicit" +
                                " assumption; never invent facts that are not grounded",
                        voice = "specific and evidence-grounded",
                    ),
                llm = LlmOptions.withLlmForRole("contract-extractor"),
            )
    }
}

data class BpmnContractConfig(
    val maxAssumptions: Int = 10,
)

data class BpmnLoggingConfig(
    val dumpArtifacts: Boolean = false,
    val artifactPreviewLength: Int = DEFAULT_ARTIFACT_PREVIEW_LENGTH,
) {
    companion object {
        const val DEFAULT_ARTIFACT_PREVIEW_LENGTH = 8000
    }
}

data class BpmnRepairConfig(
    val abbreviations: Map<String, String> = emptyMap(),
)
