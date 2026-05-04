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

            val response = promptRunner.createObject(messages = messages, outputClass = String::class.java)
            logger.debug("Attempt {} raw LLM response:\n{}", attempt, response)

            val xml = extractXml(response)
            if (xml == null) {
                logger.warn("Attempt {}: no XML block detected in LLM response", attempt)
                messages.add(AssistantMessage(response))
                messages.add(UserMessage(
                    "Your response did not contain a valid XML block. " +
                    "Please output only the BPMN XML inside a ```xml code block."
                ))
                continue
            }

            logger.info("Attempt {}: extracted BPMN XML, length={}", attempt, xml.length)
            logger.debug("Attempt {} extracted XML:\n{}", attempt, xml)
            messages.add(AssistantMessage(response))

            val xsdError = bpmnXsdValidator.validate(xml)
            if (xsdError != null) {
                logger.warn("Attempt {}: XSD validation failed: {}", attempt, xsdError)
                messages.add(UserMessage(
                    "The BPMN XML failed XSD validation. Fix these errors and output the corrected XML:\n\n$xsdError"
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
                    "The BPMN XML passed XSD validation but failed bpmn-lint. " +
                    "Fix these issues and output the corrected XML:\n\n$formatted"
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
            You are a BPMN 2.0 expert. Given a business process description, generate
            a valid BPMN 2.0 XML document conforming to the BPMN20.xsd schema.

            Rules:
            - Output ONLY a single XML code block — no explanation, no prose.
            - Use the namespace: xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
            - Include proper targetNamespace and exporter attributes on <definitions>.
            - Every element that needs it must have a unique id attribute.
            - Include a <bpmndi:BPMNDiagram> section with layout coordinates.
            - Use clear, descriptive names on tasks, gateways, and events.
            - Prefer sequence flows with conditions on exclusive gateways.

            If you receive validation errors, fix them and output the corrected XML.
        """.trimIndent()

        return if (request.styleGuide != null) {
            "$base\n\n---\n\n## Style guide\n\n${request.styleGuide}"
        } else {
            base
        }
    }

    private fun extractXml(text: String): String? {
        return (
            xmlBlockRegex.find(text)?.groupValues?.getOrNull(1)
                ?: fullXmlRegex.find(text)?.groupValues?.getOrNull(0)
                ?: definitionsRegex.find(text)?.groupValues?.getOrNull(0)
        )?.trim()
    }

    companion object {
        private val xmlBlockRegex = Regex("""```xml\s*([\s\S]*?)```""")
        private val fullXmlRegex = Regex("""<\?xml[\s\S]*?</definitions>""")
        private val definitionsRegex = Regex("""<definitions[\s\S]*?</definitions>""")
    }
}
