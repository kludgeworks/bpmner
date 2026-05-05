package dev.groknull.bpmner.agent

import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.PromptRunner
import com.embabel.agent.api.common.asSubProcess
import com.embabel.agent.api.common.workflow.loop.Feedback
import com.embabel.agent.api.common.workflow.loop.RepeatUntilAcceptableActionContext
import com.embabel.agent.api.common.workflow.loop.RepeatUntilAcceptableBuilder
import com.embabel.chat.AssistantMessage
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class BpmnRefinementWorkflow(
    private val config: BpmnConfig,
    private val bpmnLintService: BpmnLintService,
    private val bpmnXsdValidator: BpmnXsdValidator,
    private val bpmnConverter: BpmnDefinitionToXmlConverter,
    private val bpmnDefinitionValidator: BpmnDefinitionValidator,
) {
    private val logger = LoggerFactory.getLogger(BpmnRefinementWorkflow::class.java)
    private val objectMapper: ObjectMapper = jacksonObjectMapper().findAndRegisterModules()

    fun refine(
        request: BpmnRequest,
        rendered: RenderedBpmn,
        context: ActionContext,
    ): ValidatedBpmnXml {
        logger.info(
            "validateAndRefineBpmn started. outputFile={}, maxAttempts={}, repairer={}, processId={}",
            request.outputFile,
            config.maxAttempts,
            config.repairer.persona.name,
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
                evaluateRefinementAttempt(attempt = evaluationContext.resultToEvaluate)
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

    // -------------------------------------------------------------------------
    // Refinement loop internals
    // -------------------------------------------------------------------------

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
                logger.debug("BPMN refinement subprocess already has an acceptable attempt; reusing last result")
                return previousAttempt
            }
            if (context.attemptHistory.attemptCount() >= config.maxAttempts) {
                logger.debug(
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
                previousAttempt.messages,
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
            repairPrompt = if (acceptable) "" else buildRepairFeedback(
                definition = attempt.definition,
                renderedXml = attempt.rendered?.xml ?: renderFailureContext(attempt),
                diagnostics = attempt.diagnostics,
            ),
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
            logger.debug("Generated BPMN XML from typed object, length={}", rendered.xml.length)
            logger.debug("Generated XML:\n{}", rendered.xml)
        }

        val diagnostics = mutableListOf<BpmnDiagnostic>()
        diagnostics.addAll(
            bpmnDefinitionValidator.validate(definition).map {
                BpmnDiagnostic(source = BpmnDiagnosticSource.GRAPH, message = it)
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
                    logger.debug("XSD validation passed")
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

    // -------------------------------------------------------------------------
    // LLM interaction
    // -------------------------------------------------------------------------

    private fun promptRunner(context: OperationContext, request: BpmnRequest): PromptRunner =
        config.repairer.promptRunner(context).withPromptContributor(request)

    private fun requestCorrection(
        promptRunner: PromptRunner,
        messages: List<Message>,
        feedback: String,
    ): Pair<BpmnDefinition, List<Message>> {
        val withFeedback = messages + UserMessage(feedback)
        val corrected = promptRunner.createObject(messages = withFeedback, outputClass = BpmnDefinition::class.java)
        return corrected to (withFeedback + AssistantMessage(serializeDefinition(corrected)))
    }

    private fun initialMessages(request: BpmnRequest, definition: BpmnDefinition): List<Message> = listOf(
        UserMessage(request.generationPrompt()),
        AssistantMessage(serializeDefinition(definition)),
    )

    // -------------------------------------------------------------------------
    // Diagnostic helpers
    // -------------------------------------------------------------------------

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

    private fun formatDiagnostic(diagnostic: BpmnDiagnostic): String = buildString {
        append("source=${diagnostic.source.name.lowercase()}")
        diagnostic.rule?.let { append(", rule=$it") }
        diagnostic.category?.let { append(", category=$it") }
        diagnostic.elementId?.let { append(", elementId=$it") }
        diagnostic.objectRef?.let { append(", objectRef=$it") }
        append(": ${diagnostic.message}")
    }

    private fun renderFailureContext(attempt: BpmnRefinementAttempt): String = buildString {
        appendLine("<render failed>")
        attempt.renderFailureMessage?.let { appendLine(it) }
    }

    private fun serializeDefinition(definition: BpmnDefinition): String =
        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(definition)
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
