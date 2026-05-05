package dev.groknull.bpmner.agent

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.Export
import com.embabel.agent.api.common.OperationContext
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
    private val objectMapper: ObjectMapper = jacksonObjectMapper().findAndRegisterModules()

    @Action(description = "Generate a typed BPMN definition from a business-process description")
    fun generateBpmnDefinition(request: BpmnRequest, context: OperationContext): BpmnDefinition {
        val selectedModel = config.model.ifBlank { "<auto>" }
        logger.info(
            "generateBpmnDefinition started. outputFile={}, configuredModel={}",
            request.outputFile,
            selectedModel,
        )

        val promptRunner = promptRunner(context, request)
        val prompt = buildGenerationPrompt(request)
        val definition = promptRunner.createObject(prompt, BpmnDefinition::class.java)
        logger.info(
            "Typed BPMN definition generated. processId={}, nodeCount={}, edgeCount={}",
            definition.processId,
            definition.nodes.size,
            definition.sequences.size,
        )
        logger.debug("Generated BPMN definition:\n{}", serializeDefinition(definition))
        return definition
    }

    @Action(description = "Render a typed BPMN definition into BPMN 2.0 XML with stable element linkage")
    fun renderBpmnXml(definition: BpmnDefinition): RenderedBpmn {
        logger.info(
            "renderBpmnXml started. processId={}, nodeCount={}, edgeCount={}",
            definition.processId,
            definition.nodes.size,
            definition.sequences.size,
        )
        val rendered = bpmnConverter.render(definition)
        logger.info(
            "renderBpmnXml completed. processId={}, xmlLength={}",
            definition.processId,
            rendered.xml.length,
        )
        logger.debug("Rendered BPMN XML:\n{}", rendered.xml)
        return rendered
    }

    @Action(description = "Validate rendered BPMN, repair the typed definition if needed, and return validated BPMN XML")
    fun validateAndRefineBpmn(
        request: BpmnRequest,
        rendered: RenderedBpmn,
        context: OperationContext,
    ): ValidatedBpmnXml {
        val selectedModel = config.model.ifBlank { "<auto>" }
        logger.info(
            "validateAndRefineBpmn started. outputFile={}, maxAttempts={}, configuredModel={}, processId={}",
            request.outputFile,
            config.maxAttempts,
            selectedModel,
            rendered.definition.processId,
        )

        val promptRunner = promptRunner(context, request)
        var currentDefinition = rendered.definition
        var currentRendered = rendered
        val messages = mutableListOf<Message>(
            UserMessage(buildGenerationPrompt(request)),
            AssistantMessage(serializeDefinition(currentDefinition)),
        )

        for (attempt in 1..config.maxAttempts) {
            logger.info(
                "BPMN validation/render attempt {}/{}. messageCount={}",
                attempt,
                config.maxAttempts,
                messages.size,
            )

            logger.debug("Attempt {} current BPMN definition:\n{}", attempt, serializeDefinition(currentDefinition))

            val graphDiagnostics = BpmnDefinitionValidator.validate(currentDefinition).map {
                BpmnDiagnostic(
                    source = BpmnDiagnosticSource.GRAPH,
                    message = it,
                )
            }
            val diagnostics = mutableListOf<BpmnDiagnostic>()
            diagnostics.addAll(graphDiagnostics)

            if (graphDiagnostics.isNotEmpty()) {
                logger.warn("Attempt {}: graph validation failed with {} issue(s)", attempt, graphDiagnostics.size)
            }

            if (diagnostics.isEmpty()) {
                currentRendered = try {
                    if (attempt == 1) {
                        currentRendered
                    } else {
                        bpmnConverter.render(currentDefinition)
                    }
                } catch (e: Exception) {
                    val renderDiagnostic = BpmnDiagnostic(
                        source = BpmnDiagnosticSource.RENDER,
                        message = e.message ?: e.javaClass.simpleName,
                    )
                    diagnostics.add(renderDiagnostic)
                    logger.warn("Attempt {}: BPMN conversion failed: {}", attempt, e.message)
                    renderedDiagnostics("Attempt $attempt", listOf(renderDiagnostic))
                    requestCorrection(promptRunner, messages, buildRepairFeedback(currentDefinition, currentRendered.xml, diagnostics)).also {
                        currentDefinition = it
                    }
                    continue
                }

                logger.info("Attempt {}: generated BPMN XML from typed object, length={}", attempt, currentRendered.xml.length)
                logger.debug("Attempt {} generated XML:\n{}", attempt, currentRendered.xml)

                diagnostics.addAll(normalizeXsdDiagnostics(currentRendered))
                if (diagnostics.none { it.source == BpmnDiagnosticSource.XSD }) {
                    logger.info("Attempt {}: XSD validation passed", attempt)
                }
            }

            if (diagnostics.none { it.source == BpmnDiagnosticSource.GRAPH || it.source == BpmnDiagnosticSource.RENDER || it.source == BpmnDiagnosticSource.XSD }) {
                val lintIssues = bpmnLintService.lint(currentRendered.xml)
                if (lintIssues == null) {
                    logger.warn("Attempt {}: bpmn-lint was unavailable; continuing without lint feedback", attempt)
                    return ValidatedBpmnXml(currentRendered.xml)
                }

                diagnostics.addAll(normalizeLintDiagnostics(lintIssues, currentRendered.elementIndex))
            }

            if (diagnostics.isEmpty()) {
                logger.info("Attempt {}: BPMN validated successfully", attempt)
                return ValidatedBpmnXml(currentRendered.xml)
            }

            renderedDiagnostics("Attempt $attempt", diagnostics)
            currentDefinition = requestCorrection(
                promptRunner,
                messages,
                buildRepairFeedback(currentDefinition, currentRendered.xml, diagnostics),
            )
        }

        logger.error("validateAndRefineBpmn failed after {} attempts", config.maxAttempts)
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

    private fun promptRunner(context: OperationContext, request: BpmnRequest) = if (config.model.isNotBlank()) {
        context.ai().withLlm(config.model)
    } else {
        context.ai().withAutoLlm()
    }.withSystemPrompt(buildSystemPrompt(request))

    private fun buildGenerationPrompt(request: BpmnRequest): String = buildString {
        appendLine("Generate a BPMN definition object for this business process.")
        appendLine()
        appendLine("Business process description:")
        appendLine(request.processDescription)
    }

    private fun requestCorrection(
        promptRunner: com.embabel.agent.api.common.PromptRunner,
        messages: MutableList<Message>,
        feedback: String,
    ): BpmnDefinition {
        messages.add(UserMessage(feedback))
        val corrected = promptRunner.createObject(messages = messages, outputClass = BpmnDefinition::class.java)
        messages.add(AssistantMessage(serializeDefinition(corrected)))
        return corrected
    }

    private fun serializeDefinition(definition: BpmnDefinition): String =
        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(definition)

    private fun normalizeXsdDiagnostics(rendered: RenderedBpmn): List<BpmnDiagnostic> =
        bpmnXsdValidator.validateDetailed(rendered.xml).map { issue ->
            val elementId = issue.elementId?.takeIf { rendered.elementIndex.knownElementIds().contains(it) }
            BpmnDiagnostic(
                source = BpmnDiagnosticSource.XSD,
                message = issue.message,
                elementId = elementId,
                objectRef = rendered.elementIndex.objectRefForElementId(elementId),
            )
        }

    private fun normalizeLintDiagnostics(
        lintIssues: List<LintIssue>,
        elementIndex: BpmnElementIndex,
    ): List<BpmnDiagnostic> =
        lintIssues.map { issue ->
            val elementId = issue.id?.takeIf { elementIndex.knownElementIds().contains(it) }
            BpmnDiagnostic(
                source = BpmnDiagnosticSource.LINT,
                message = issue.message,
                rule = issue.rule,
                category = issue.category,
                elementId = elementId,
                objectRef = elementIndex.objectRefForElementId(elementId),
            )
        }

    private fun buildRepairFeedback(
        definition: BpmnDefinition,
        renderedXml: String,
        diagnostics: List<BpmnDiagnostic>,
    ): String = buildString {
        appendLine("The BPMN definition needs repair. Return the full corrected BpmnDefinition object.")
        appendLine()
        appendLine("Use the typed BPMN definition as the canonical edit surface.")
        appendLine("Use the rendered BPMN XML only as supporting context when diagnostics refer to rendered elements.")
        appendLine()
        appendLine("Current canonical BpmnDefinition JSON:")
        appendLine(serializeDefinition(definition))
        appendLine()
        appendLine("Rendered BPMN XML:")
        appendLine(renderedXml)
        appendLine()
        appendLine("Diagnostics:")
        diagnostics.forEach { diagnostic ->
            appendLine("- ${formatDiagnostic(diagnostic)}")
        }
    }

    private fun formatDiagnostic(diagnostic: BpmnDiagnostic): String =
        buildString {
            append("source=${diagnostic.source.name.lowercase()}")
            diagnostic.rule?.let { append(", rule=$it") }
            diagnostic.category?.let { append(", category=$it") }
            diagnostic.elementId?.let { append(", elementId=$it") }
            diagnostic.objectRef?.let { append(", objectRef=$it") }
            append(": ${diagnostic.message}")
        }

    private fun renderedDiagnostics(prefix: String, diagnostics: List<BpmnDiagnostic>) {
        diagnostics.forEach { diagnostic ->
            logger.warn(
                "{} diagnostic. source={}, rule={}, category={}, elementId={}, objectRef={}, message={}",
                prefix,
                diagnostic.source.name.lowercase(),
                diagnostic.rule ?: "-",
                diagnostic.category ?: "-",
                diagnostic.elementId ?: "-",
                diagnostic.objectRef ?: "-",
                diagnostic.message,
            )
        }
    }

    private fun buildSystemPrompt(request: BpmnRequest): String {
        val base = """
            You are a BPMN process design expert. Given a business process description, generate
            a typed BPMN process definition object that can be converted to valid BPMN 2.0 XML.

            Rules:
            - Return a single process definition object with processId, processName, nodes, and sequences.
            - Every node id and sequence id must be unique.
            - Every sequence sourceRef and targetRef must reference an existing node id.
            - Include at least one START_EVENT and one END_EVENT.
            - Use clear, descriptive business names on nodes.
            - Keep process topology coherent with no dangling references or self-loop sequence flows.
            - Every node must include explicit bounds with x, y, width, and height.
            - Every sequence must include at least two waypoints that define its diagram path.
            - Use conditionExpression on conditional gateway branches when needed.
            - The layout should be coherent and readable because it will be emitted directly into BPMNDI.

            If you receive validation errors, fix them and return the full corrected object.
        """.trimIndent()

        return if (request.styleGuide != null) {
            "$base\n\n---\n\n## Style guide\n\n${request.styleGuide}"
        } else {
            base
        }
    }
}
