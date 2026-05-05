package dev.groknull.bpmner.agent

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.Export
import com.embabel.agent.api.common.OperationContext
import com.embabel.chat.AssistantMessage
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.File

@ConfigurationProperties("bpmner")
data class BpmnConfig(
    val maxAttempts: Int = 5,
    val model: String = "",
)

@Agent(description = "Generate a valid BPMN 2.0 diagram from a plain-language business process description")
class BpmnGeneratorAgent(
    private val config: BpmnConfig,
    private val bpmnLintService: BpmnLintService,
    private val bpmnXsdValidator: BpmnXsdValidator,
    private val bpmnConverter: BpmnDefinitionToXmlConverter,
) {

    private val logger = LoggerFactory.getLogger(BpmnGeneratorAgent::class.java)

    @Action(description = "Call LLM with multi-turn feedback loop to produce validated BPMN XML")
    fun generateValidBpmn(request: BpmnRequest, context: OperationContext): ValidatedBpmnXml {
        val selectedModel = config.model.ifBlank { "<auto>" }
        logger.info(
            "generateValidBpmn started. outputFile={}, maxAttempts={}, configuredModel={}",
            request.outputFile,
            config.maxAttempts,
            selectedModel,
        )

        val promptRunner = if (config.model.isNotBlank()) {
            context.ai().withLlm(config.model)
        } else {
            context.ai().withAutoLlm()
        }.withSystemPrompt(buildSystemPrompt(request))

        val messages = mutableListOf<Message>(
            UserMessage(
                "Generate a BPMN 2.0 diagram for the following business process:\n\n${request.processDescription}"
            )
        )

        for (attempt in 1..config.maxAttempts) {
            logger.info(
                "BPMN generation attempt {}/{}. messageCount={}",
                attempt,
                config.maxAttempts,
                messages.size,
            )

            val response = promptRunner.createObject(messages = messages, outputClass = BpmnDefinition::class.java)
            logger.debug("Attempt {} typed LLM response: {}", attempt, response)
            messages.add(AssistantMessage(response.toString()))

            val dtoErrors = BpmnDefinitionValidator.validate(response)
            if (dtoErrors.isNotEmpty()) {
                val formatted = dtoErrors.joinToString("\n") { "- $it" }
                logger.warn("Attempt {}: DTO validation failed with {} issue(s)", attempt, dtoErrors.size)
                messages.add(UserMessage(
                    "Your BPMN object output is structurally invalid. Fix these issues and return a corrected object:\n\n$formatted"
                ))
                continue
            }

            val xml = try {
                bpmnConverter.toXml(response)
            } catch (e: Exception) {
                logger.warn("Attempt {}: BPMN conversion failed: {}", attempt, e.message)
                messages.add(UserMessage(
                    "Your BPMN object could not be converted to BPMN 2.0 model XML. " +
                        "Fix structural consistency (node ids/types/edges) and try again. Conversion error:\n\n${e.message}"
                ))
                continue
            }

            logger.info("Attempt {}: generated BPMN XML from typed object, length={}", attempt, xml.length)
            logger.debug("Attempt {} generated XML:\n{}", attempt, xml)

            val xsdError = bpmnXsdValidator.validate(xml)
            if (xsdError != null) {
                logger.warn("Attempt {}: XSD validation failed: {}", attempt, xsdError)
                messages.add(UserMessage(
                    "The BPMN XML produced from your object failed XSD validation. " +
                        "Fix the process definition object so regenerated XML satisfies these errors:\n\n$xsdError"
                ))
                continue
            }

            logger.info("Attempt {}: XSD validation passed", attempt)

            val lintIssues = bpmnLintService.lint(xml)
            if (lintIssues == null) {
                logger.warn("Attempt {}: bpmn-lint was unavailable; continuing without lint feedback", attempt)
            } else if (lintIssues.isNotEmpty()) {
                val formatted = lintIssues.joinToString("\n") { "[${it.rule}] ${it.message}" }
                logger.warn("Attempt {}: bpmn-lint found {} issue(s)", attempt, lintIssues.size)
                logger.warn("Attempt {}: bpmn-lint issues:\n{}", attempt, formatted)
                messages.add(UserMessage(
                    "The BPMN XML produced from your object passed XSD but failed bpmn-lint. " +
                        "Fix these issues in the process definition object and return the corrected object:\n\n$formatted"
                ))
                continue
            } else {
                logger.info("Attempt {}: bpmn-lint passed with no issues", attempt)
            }

            logger.info("Attempt {}: BPMN validated successfully", attempt)
            return ValidatedBpmnXml(xml)
        }

        logger.error("generateValidBpmn failed after {} attempts", config.maxAttempts)
        error("Failed to produce valid BPMN after ${config.maxAttempts} attempts")
    }

    @AchievesGoal(
        description = "Write validated BPMN 2.0 XML to the requested output file",
        export = Export(name = "generateBpmn", remote = true, startingInputTypes = [BpmnRequest::class]),
    )
    @Action(description = "Write the validated BPMN XML to disk")
    fun writeBpmn(request: BpmnRequest, bpmn: ValidatedBpmnXml): BpmnResult {
        logger.info(
            "writeBpmn started. outputFile={}, xmlLength={}",
            request.outputFile,
            bpmn.xml.length,
        )
        File(request.outputFile).writeText(bpmn.xml, Charsets.UTF_8)
        logger.info("BPMN written to {}", request.outputFile)
        return BpmnResult(outputFile = request.outputFile, xml = bpmn.xml)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun buildSystemPrompt(request: BpmnRequest): String {
        val base = """
            You are a BPMN process design expert. Given a business process description, generate
            a typed process graph object that can be converted to valid BPMN 2.0 XML.

            Rules:
            - Return a single process definition object with processId, processName, nodes, and sequences.
            - Every node id and sequence id must be unique.
            - Every sequence sourceRef and targetRef must reference an existing node id.
            - Include at least one START_EVENT and one END_EVENT.
            - Use clear, descriptive business names on nodes.
            - Keep process topology coherent (no dangling references).

            If you receive validation errors, fix them and return the corrected object.
        """.trimIndent()

        return if (request.styleGuide != null) {
            "$base\n\n---\n\n## Style guide\n\n${request.styleGuide}"
        } else {
            base
        }
    }
}
