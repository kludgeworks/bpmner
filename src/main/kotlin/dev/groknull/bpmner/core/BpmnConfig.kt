package dev.groknull.bpmner.core

import com.embabel.agent.api.common.Actor
import com.embabel.agent.prompt.persona.Persona
import com.embabel.common.ai.model.LlmOptions
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("bpmner")
data class BpmnConfig(
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    val generator: Actor<Persona> = DEFAULT_GENERATOR,
    val repairer: Actor<Persona> = DEFAULT_REPAIRER,
    val readinessAssessor: Actor<Persona> = DEFAULT_READINESS_ASSESSOR,
    @field:Valid
    val readiness: BpmnReadinessConfig = BpmnReadinessConfig(),
    val contractExtractor: Actor<Persona> = DEFAULT_CONTRACT_EXTRACTOR,
    val contract: BpmnContractConfig = BpmnContractConfig(),
    val logging: BpmnLoggingConfig = BpmnLoggingConfig(),
    val repair: BpmnRepairConfig = BpmnRepairConfig(),
    val labelRepairer: Actor<Persona> = DEFAULT_LABEL_REPAIRER,
    val patchRepairer: Actor<Persona> = DEFAULT_PATCH_REPAIRER,
    val rewriteRepairer: Actor<Persona> = DEFAULT_REWRITE_REPAIRER,
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

        val DEFAULT_LABEL_REPAIRER =
            Actor(
                persona =
                    Persona(
                        name = "BPMN Label Copy Editor",
                        persona = "You are a fast, detail-oriented BPMN copy editor",
                        objective =
                            "Fix naming and label capitalization rules by providing targeted node and edge patches",
                        voice = "concise and exact",
                    ),
                llm = LlmOptions.withLlmForRole("label-repairer"),
            )

        val DEFAULT_PATCH_REPAIRER =
            Actor(
                persona =
                    Persona(
                        name = "BPMN Patch Repair Specialist",
                        persona = "You are a strict BPMN 2.0 graph topology validator and patch expert",
                        objective =
                            "Fix structural and routing validation errors by adding or removing" +
                                " specific elements without rewriting the whole definition",
                        voice = "concise and exact",
                    ),
                llm = LlmOptions.withLlmForRole("repair-patch"),
            )

        val DEFAULT_REWRITE_REPAIRER =
            Actor(
                persona =
                    Persona(
                        name = "BPMN Full Rewrite Specialist",
                        persona = "You are an expert BPMN 2.0 validator who specializes in holistic process restructuring",
                        objective =
                            "Fix complex, cascading validation errors by rewriting the complete BPMN definition",
                        voice = "concise and exact",
                    ),
                llm = LlmOptions.withLlmForRole("repair-rewrite"),
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
        val DEFAULT_READINESS_ASSESSOR =
            Actor(
                persona =
                    Persona(
                        name = "BPMN Readiness Assessor",
                        persona = "You are a conservative business process intake reviewer",
                        objective =
                            "Assess whether source text contains enough grounded process detail" +
                                " for BPMN generation without inventing missing facts",
                        voice = "specific and evidence-grounded",
                    ),
                llm = LlmOptions.withLlmForRole("readiness-assessor"),
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

data class BpmnReadinessConfig(
    @field:Min(0)
    @field:Max(100)
    val readyThreshold: Int = 75,
    @field:Min(0)
    @field:Max(100)
    val clarificationThreshold: Int = 40,
    @field:Min(1)
    val minimumActivityCount: Int = 2,
    @field:Min(1)
    val maxClarificationQuestions: Int = 5,
)

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
