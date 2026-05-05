package dev.groknull.bpmner.agent

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.Export
import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.asSubProcess
import com.embabel.agent.api.common.workflow.loop.Feedback
import com.embabel.agent.api.common.workflow.loop.RepeatUntilAcceptableActionContext
import com.embabel.agent.api.common.workflow.loop.RepeatUntilAcceptableBuilder
import com.embabel.agent.api.common.workflow.loop.EvaluationActionContext
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
        context: ActionContext,
    ): ValidatedBpmnXml {
        val selectedModel = config.model.ifBlank { "<auto>" }
        logger.info(
            "validateAndRefineBpmn started. outputFile={}, maxAttempts={}, configuredModel={}, processId={}",
            request.outputFile,
            config.maxAttempts,
            selectedModel,
            rendered.definition.processId,
        )

        val refinementWorkflow = RepeatUntilAcceptableBuilder
            .returning(BpmnRefinementAttempt::class.java)
            .consuming(Unit::class.java)
            .withFeedbackClass(BpmnRefinementFeedback::class.java)
            .withMaxIterations(config.maxAttempts)
            .repeating { attemptContext ->
                nextRefinementAttempt(
                    request = request,
                    initialRendered = rendered,
                    repairContext = context,
                    context = attemptContext,
                )
            }
            .withEvaluator { evaluationContext ->
                evaluateRefinementAttempt(
                    attempt = evaluationContext.resultToEvaluate,
                )
            }
            .withAcceptanceCriteria { acceptanceContext -> acceptanceContext.feedback.acceptable }

        val finalAttempt: BpmnRefinementAttempt = context.asSubProcess(
            refinementWorkflow.buildAgent(
                name = "bpmn-refinement-workflow",
                description = "Repair BPMN definitions until external validation succeeds",
            )
        )

        if (!finalAttempt.isSuccessful()) {
            logger.error("validateAndRefineBpmn failed after {} attempts", config.maxAttempts)
            error("Failed to produce valid BPMN after ${config.maxAttempts} attempts")
        }

        logger.info("BPMN validated successfully via workflow subprocess")
        return finalAttempt.toValidatedBpmnXml()
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
    ): Pair<BpmnDefinition, List<Message>> {
        messages.add(UserMessage(feedback))
        val corrected = promptRunner.createObject(messages = messages, outputClass = BpmnDefinition::class.java)
        messages.add(AssistantMessage(serializeDefinition(corrected)))
        return corrected to messages.toList()
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

    private fun nextRefinementAttempt(
        request: BpmnRequest,
        initialRendered: RenderedBpmn,
        repairContext: OperationContext,
        context: RepeatUntilAcceptableActionContext<Unit, BpmnRefinementAttempt, BpmnRefinementFeedback>,
    ): BpmnRefinementAttempt {
        val previousAttempt = context.lastAttempt()?.result
        return if (previousAttempt == null) {
            evaluateCandidate(
                definition = initialRendered.definition,
                rendered = initialRendered,
                messages = initialMessages(request, initialRendered.definition),
            )
        } else {
            val feedback = context.lastAttempt()?.feedback ?: error("Expected feedback for prior refinement attempt")
            if (previousAttempt.isSuccessful() || feedback.acceptable) {
                logger.info("BPMN refinement subprocess already has an acceptable attempt; reusing last result")
                return previousAttempt
            }
            if (context.attemptHistory.attemptCount() >= config.maxAttempts) {
                logger.info(
                    "BPMN refinement subprocess reached max attempts ({}); reusing last result for consolidation",
                    config.maxAttempts,
                )
                return previousAttempt
            }
            if (feedback.repairPrompt.isBlank()) {
                logger.warn("BPMN refinement feedback had no repair prompt; reusing last attempt")
                return previousAttempt
            }
            logger.info(
                "BPMN refinement subprocess attempt {} of {}",
                context.attemptHistory.attemptCount() + 1,
                config.maxAttempts,
            )
            val (correctedDefinition, updatedMessages) = requestCorrection(
                promptRunner(repairContext, request),
                previousAttempt.messages.toMutableList(),
                feedback.repairPrompt,
            )
            val rendered = try {
                bpmnConverter.render(correctedDefinition)
            } catch (e: Exception) {
                return evaluateCandidate(
                    definition = correctedDefinition,
                    rendered = null,
                    messages = updatedMessages,
                    renderFailureMessage = e.message ?: e.javaClass.simpleName,
                )
            }

            evaluateCandidate(
                definition = correctedDefinition,
                rendered = rendered,
                messages = updatedMessages,
            )
        }
    }

    private fun evaluateRefinementAttempt(attempt: BpmnRefinementAttempt): BpmnRefinementFeedback {
        if (attempt.diagnostics.isNotEmpty()) {
            renderedDiagnostics("Refinement workflow", attempt.diagnostics)
        }

        val acceptable = attempt.diagnostics.isEmpty()
        return BpmnRefinementFeedback(
            score = if (acceptable) 1.0 else 0.0,
            acceptable = acceptable,
            diagnostics = attempt.diagnostics,
            repairPrompt = if (acceptable) "" else {
                buildRepairFeedback(
                    definition = attempt.definition,
                    renderedXml = attempt.rendered?.xml ?: renderFailureContext(attempt),
                    diagnostics = attempt.diagnostics,
                )
            },
        )
    }

    private fun evaluateCandidate(
        definition: BpmnDefinition,
        rendered: RenderedBpmn?,
        messages: List<Message>,
        renderFailureMessage: String? = null,
    ): BpmnRefinementAttempt {
        logger.debug("Refinement candidate BPMN definition:\n{}", serializeDefinition(definition))
        if (rendered != null) {
            logger.info("Generated BPMN XML from typed object, length={}", rendered.xml.length)
            logger.debug("Generated XML:\n{}", rendered.xml)
        }

        val diagnostics = mutableListOf<BpmnDiagnostic>()
        diagnostics.addAll(
            BpmnDefinitionValidator.validate(definition).map {
                BpmnDiagnostic(
                    source = BpmnDiagnosticSource.GRAPH,
                    message = it,
                )
            }
        )

        if (diagnostics.none { it.source == BpmnDiagnosticSource.GRAPH }) {
            if (renderFailureMessage != null || rendered == null) {
                diagnostics.add(
                    BpmnDiagnostic(
                        source = BpmnDiagnosticSource.RENDER,
                        message = renderFailureMessage ?: "Unknown BPMN rendering error",
                    )
                )
            } else {
                diagnostics.addAll(normalizeXsdDiagnostics(rendered))
                if (diagnostics.none { it.source == BpmnDiagnosticSource.XSD }) {
                    logger.info("XSD validation passed")
                    val lintIssues = bpmnLintService.lint(rendered.xml)
                    if (lintIssues == null) {
                        logger.warn("bpmn-lint was unavailable; continuing without lint feedback")
                    } else {
                        diagnostics.addAll(normalizeLintDiagnostics(lintIssues, rendered.elementIndex))
                    }
                }
            }
        }

        return BpmnRefinementAttempt(
            definition = definition,
            rendered = rendered,
            diagnostics = diagnostics,
            validatedXml = if (diagnostics.isEmpty()) rendered?.xml else null,
            messages = messages,
            renderFailureMessage = renderFailureMessage,
        )
    }

    private fun initialMessages(
        request: BpmnRequest,
        definition: BpmnDefinition,
    ): List<Message> = listOf(
        UserMessage(buildGenerationPrompt(request)),
        AssistantMessage(serializeDefinition(definition)),
    )

    private fun renderFailureContext(attempt: BpmnRefinementAttempt): String =
        buildString {
            appendLine("<render failed>")
            attempt.renderFailureMessage?.let { appendLine(it) }
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

private data class BpmnRefinementAttempt(
    val definition: BpmnDefinition,
    val rendered: RenderedBpmn?,
    val diagnostics: List<BpmnDiagnostic>,
    val validatedXml: String?,
    val messages: List<Message>,
    val renderFailureMessage: String? = null,
) {
    fun isSuccessful(): Boolean = validatedXml != null && diagnostics.isEmpty()

    fun toValidatedBpmnXml(): ValidatedBpmnXml = ValidatedBpmnXml(
        xml = validatedXml ?: error("No validated BPMN XML available"),
        diagnostics = diagnostics,
    )
}

private data class BpmnRefinementFeedback(
    override val score: Double,
    val acceptable: Boolean,
    val diagnostics: List<BpmnDiagnostic>,
    val repairPrompt: String,
) : Feedback
