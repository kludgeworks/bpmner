package dev.groknull.bpmner.agent

import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.PromptRunner
import com.embabel.chat.AssistantMessage
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import com.embabel.common.ai.prompt.PromptContributor
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.security.MessageDigest
import kotlin.math.min

@Component
class BpmnRefinementWorkflow(
    private val config: BpmnConfig,
    private val bpmnLintService: BpmnLintService,
    private val bpmnXsdValidator: BpmnXsdValidator,
    private val bpmnConverter: BpmnDefinitionToXmlConverter,
    private val bpmnDefinitionValidator: BpmnDefinitionValidator,
    private val bpmnPatchApplier: BpmnPatchApplier,
    private val topologyRepair: BpmnTopologyRepair,
) {
    private val logger = LoggerFactory.getLogger(BpmnRefinementWorkflow::class.java)
    private val objectMapper: ObjectMapper = jacksonObjectMapper().findAndRegisterModules()

    fun refine(
        request: BpmnRequest,
        graph: LaidOutProcessGraph,
        rendered: RenderedBpmn,
        context: ActionContext,
    ): ValidatedBpmnXml {
        val maxEvaluations = config.maxAttempts.coerceAtLeast(1)
        val history = mutableListOf<BpmnRefinementAttemptRecord>()
        val initialMessages = initialMessages(request, rendered.definition)
        var currentGraph = graph
        var currentAttempt = evaluateCandidate(
            graph = currentGraph,
            definition = rendered.definition,
            rendered = rendered,
            messages = initialMessages,
            repairAttempts = 0,
        )
        var currentRecord = currentAttempt.toRecord(attemptNumber = history.size + 1)
        history += currentRecord
        if (currentAttempt.isSuccessful()) {
            context.updateProgress("Validation passed after ${currentAttempt.repairAttempts} repair attempt(s)")
            return currentAttempt.toValidatedBpmnXml()
        }

        val invalidDefinitionFingerprints = mutableSetOf(currentRecord.definitionFingerprint)
        while (history.size < maxEvaluations) {
            logDiagnosticSummary(currentAttempt.globalDiagnostics.diagnostics)
            val globalFeedbackDiagnostics = currentAttempt.globalDiagnostics
            context.updateProgress(
                "Repair attempt ${currentAttempt.repairAttempts + 1}/${maxEvaluations - 1}: " +
                "graph=${globalFeedbackDiagnostics.countFor(BpmnDiagnosticSource.GRAPH)}, " +
                "xsd=${globalFeedbackDiagnostics.countFor(BpmnDiagnosticSource.XSD)}, " +
                "lint=${globalFeedbackDiagnostics.countFor(BpmnDiagnosticSource.LINT)}"
            )
            val repairPromptRunner = promptRunner(context, request).let { runner ->
                val docsPrompt = lintRuleDocsPrompt(currentAttempt.diagnostics)
                if (docsPrompt != null) runner.withPromptContributor(docsPrompt) else runner
            }

            var repairPromptText: String
            val deterministicPatch = topologyRepair.buildTopologyPatch(currentAttempt.definition, currentAttempt.diagnostics)
            val (correctedDefinition, updatedMessages) = if (deterministicPatch != null) {
                repairPromptText = "deterministic-topology-repair:${deterministicPatch.reason}"
                when (val patchResult = bpmnPatchApplier.apply(currentAttempt.definition, deterministicPatch)) {
                    is PatchApplicationResult.Success -> {
                        logger.info(
                            "Topology repair applied deterministically: ops={}, reason={}",
                            deterministicPatch.operations.size,
                            deterministicPatch.reason ?: "-",
                        )
                        patchResult.definition to currentAttempt.messages
                    }
                    is PatchApplicationResult.NoOp -> failRefinement(
                        maxEvaluations = maxEvaluations,
                        history = history,
                        reason = "deterministic topology patch was a no-op on attempt ${currentAttempt.repairAttempts + 1}",
                    )
                    is PatchApplicationResult.Failure -> {
                        logger.warn(
                            "Deterministic topology repair failed ({}), falling back to full correction",
                            patchResult.reason,
                        )
                        val repairPrompt = buildRepairFeedback(
                            definition = currentAttempt.definition,
                            renderedXml = currentAttempt.rendered?.xml ?: renderFailureContext(currentAttempt),
                            diagnostics = currentAttempt.diagnostics,
                        )
                        repairPromptText = repairPrompt
                        requestCorrection(repairPromptRunner, currentAttempt.messages, repairPrompt)
                    }
                }
            } else if (isPatchable(currentAttempt.diagnostics)) {
                val patchFeedback = buildPatchFeedback(currentAttempt.definition, currentAttempt.diagnostics)
                repairPromptText = patchFeedback
                val patch = requestPatchCorrection(repairPromptRunner, currentAttempt.messages, patchFeedback)
                when (val patchResult = bpmnPatchApplier.apply(currentAttempt.definition, patch)) {
                    is PatchApplicationResult.Success -> {
                        logger.info(
                            "Patch repair applied: ops={}, reason={}",
                            patch.operations.size,
                            patch.reason ?: "-",
                        )
                        patchResult.definition to (currentAttempt.messages + UserMessage(patchFeedback))
                    }
                    is PatchApplicationResult.NoOp -> failRefinement(
                        maxEvaluations = maxEvaluations,
                        history = history,
                        reason = "patch was a no-op on attempt ${currentAttempt.repairAttempts + 1}",
                    )
                    is PatchApplicationResult.Failure -> {
                        logger.warn(
                            "Patch application failed ({}), falling back to full correction",
                            patchResult.reason,
                        )
                        val repairPrompt = buildRepairFeedback(
                            definition = currentAttempt.definition,
                            renderedXml = currentAttempt.rendered?.xml ?: renderFailureContext(currentAttempt),
                            diagnostics = currentAttempt.diagnostics,
                        )
                        repairPromptText = repairPrompt
                        requestCorrection(repairPromptRunner, currentAttempt.messages, repairPrompt)
                    }
                }
            } else {
                val repairPrompt = buildRepairFeedback(
                    definition = currentAttempt.definition,
                    renderedXml = currentAttempt.rendered?.xml ?: renderFailureContext(currentAttempt),
                    diagnostics = currentAttempt.diagnostics,
                )
                repairPromptText = repairPrompt
                if (repairPrompt.isBlank()) {
                    failRefinement(maxEvaluations = maxEvaluations, history = history, reason = "empty repair prompt")
                }
                requestCorrection(repairPromptRunner, currentAttempt.messages, repairPrompt)
            }

            val correctedDefinitionFingerprint = definitionFingerprint(correctedDefinition)
            if (correctedDefinitionFingerprint == currentRecord.definitionFingerprint) {
                failRefinement(
                    maxEvaluations = maxEvaluations,
                    history = history,
                    reason = "unchanged patch on repair attempt ${currentAttempt.repairAttempts + 1}",
                )
            }
            if (correctedDefinitionFingerprint in invalidDefinitionFingerprints) {
                failRefinement(
                    maxEvaluations = maxEvaluations,
                    history = history,
                    reason = "repeated invalid output on repair attempt ${currentAttempt.repairAttempts + 1}",
                )
            }

            currentGraph = currentGraph.copy(definition = correctedDefinition)
            var renderFailureMessage: String? = null
            val correctedRendered = try {
                bpmnConverter.render(currentGraph)
            } catch (e: Exception) {
                renderFailureMessage = e.message ?: e.javaClass.simpleName
                null
            }
            val nextAttempt = evaluateCandidate(
                graph = currentGraph,
                definition = correctedDefinition,
                rendered = correctedRendered,
                messages = updatedMessages,
                renderFailureMessage = renderFailureMessage,
                repairAttempts = currentAttempt.repairAttempts + 1,
            )
            val nextRecord = nextAttempt.toRecord(
                attemptNumber = history.size + 1,
                repairPromptFingerprint = textFingerprint(repairPromptText),
            )
            history += nextRecord
            if (nextAttempt.isSuccessful()) {
                context.updateProgress("Validation passed after ${nextAttempt.repairAttempts} repair attempt(s)")
                return nextAttempt.toValidatedBpmnXml()
            }
            if (nextRecord.diagnosticFingerprint == currentRecord.diagnosticFingerprint) {
                failRefinement(
                    maxEvaluations = maxEvaluations,
                    history = history,
                    reason = "unchanged diagnostics after repair attempt ${nextAttempt.repairAttempts}",
                )
            }

            invalidDefinitionFingerprints += nextRecord.definitionFingerprint
            currentAttempt = nextAttempt
            currentRecord = nextRecord
        }

        failRefinement(
            maxEvaluations = maxEvaluations,
            history = history,
            reason = "exhausted BPMN repair attempts",
        )
    }

    // -------------------------------------------------------------------------
    // Refinement loop internals
    // -------------------------------------------------------------------------

    private fun evaluateCandidate(
        graph: LaidOutProcessGraph,
        definition: BpmnDefinition,
        rendered: RenderedBpmn?,
        messages: List<Message>,
        renderFailureMessage: String? = null,
        repairAttempts: Int,
    ): BpmnRefinementAttempt {
        val diagnostics = mutableListOf<BpmnDiagnostic>()
        diagnostics.addAll(
            bpmnDefinitionValidator.validate(definition).map {
                scopedDiagnostic(
                    graph = graph,
                    diagnostic = BpmnDiagnostic(
                        source = BpmnDiagnosticSource.GRAPH,
                        message = it,
                    )
                )
            }
        )

        if (diagnostics.none { it.source == BpmnDiagnosticSource.GRAPH }) {
            if (renderFailureMessage != null || rendered == null) {
                diagnostics += scopedDiagnostic(
                    graph = graph,
                    diagnostic = BpmnDiagnostic(
                        source = BpmnDiagnosticSource.RENDER,
                        message = renderFailureMessage ?: "Unknown BPMN rendering error",
                    )
                )
            } else {
                diagnostics.addAll(normalizeXsdDiagnostics(rendered, graph))
                if (diagnostics.none { it.source == BpmnDiagnosticSource.XSD }) {
                    val lintIssues = bpmnLintService.lint(rendered.xml, BpmnLintPhase.SEMANTIC_PRE_LAYOUT)
                    if (lintIssues == null) {
                        logger.warn("bpmn-lint was unavailable; continuing without lint feedback")
                    } else {
                        diagnostics.addAll(normalizeLintDiagnostics(lintIssues, rendered.elementIndex, graph))
                    }
                }
            }
        }

        val infrastructureDiagnostics = diagnostics.filter { it.isValidatorInfrastructureFailure() }
        if (infrastructureDiagnostics.isNotEmpty()) {
            logDiagnosticSummary(infrastructureDiagnostics)
            throw BpmnValidatorInfrastructureException(
                buildValidatorInfrastructureMessage(infrastructureDiagnostics)
            )
        }

        val globalDiagnostics = GlobalDiagnostics(diagnostics)
        if (diagnostics.isEmpty()) {
            logger.info(
                "Validation summary: graph=0, xsd=0, lint=0, repairScope=none, accepted=true, repairs={}",
                repairAttempts,
            )
        } else {
            logger.info(
                "Validation summary: graph={}, xsd={}, lint={}, repairScope={}, accepted=false, repairs={}",
                globalDiagnostics.countFor(BpmnDiagnosticSource.GRAPH),
                globalDiagnostics.countFor(BpmnDiagnosticSource.XSD),
                globalDiagnostics.countFor(BpmnDiagnosticSource.LINT),
                diagnostics.groupingBy { it.repairScope ?: BpmnRepairScope.FULL_PROCESS }.eachCount()
                    .entries.joinToString(",") { "${it.key.name.lowercase()}=${it.value}" },
                repairAttempts,
            )
        }
        logArtifactsIfEnabled(definition, rendered)

        return BpmnRefinementAttempt(
            definition = definition,
            rendered = rendered,
            diagnostics = diagnostics,
            globalDiagnostics = globalDiagnostics,
            validatedXml = if (diagnostics.isEmpty()) rendered?.xml else null,
            messages = messages,
            renderFailureMessage = renderFailureMessage,
            repairAttempts = repairAttempts,
        )
    }

    private fun BpmnRefinementAttempt.toRecord(
        attemptNumber: Int,
        repairPromptFingerprint: String? = null,
    ): BpmnRefinementAttemptRecord =
        BpmnRefinementAttemptRecord(
            attemptNumber = attemptNumber,
            repairAttempts = repairAttempts,
            graphDiagnostics = globalDiagnostics.countFor(BpmnDiagnosticSource.GRAPH),
            renderDiagnostics = globalDiagnostics.countFor(BpmnDiagnosticSource.RENDER),
            xsdDiagnostics = globalDiagnostics.countFor(BpmnDiagnosticSource.XSD),
            lintDiagnostics = globalDiagnostics.countFor(BpmnDiagnosticSource.LINT),
            diagnosticFingerprint = diagnosticFingerprint(diagnostics),
            definitionFingerprint = definitionFingerprint(definition),
            repairPromptFingerprint = repairPromptFingerprint,
        )

    private fun failRefinement(
        maxEvaluations: Int,
        history: List<BpmnRefinementAttemptRecord>,
        reason: String,
    ): Nothing {
        val compactHistory = history.joinToString(" | ") { it.compact() }
        logger.error(
            "BPMN validation failed after {} candidate evaluation(s): {}; history={}",
            history.size,
            reason,
            compactHistory,
        )
        error("Failed to produce valid BPMN after $maxEvaluations attempts: $reason; history=$compactHistory")
    }

    private fun definitionFingerprint(definition: BpmnDefinition): String =
        textFingerprint(serializeDefinition(definition))

    private fun diagnosticFingerprint(diagnostics: List<BpmnDiagnostic>): String =
        textFingerprint(
            diagnostics
                .map { diagnostic ->
                    listOf(
                        diagnostic.source.name,
                        diagnostic.rule.orEmpty(),
                        diagnostic.category.orEmpty(),
                        diagnostic.elementId.orEmpty(),
                        diagnostic.objectRef.orEmpty(),
                        diagnostic.repairScope?.name.orEmpty(),
                        diagnostic.ownerRef.orEmpty(),
                        diagnostic.message,
                    ).joinToString("\u001f")
                }
                .sorted()
                .joinToString("\u001e")
        )

    private fun textFingerprint(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(12)
    }

    // -------------------------------------------------------------------------
    // LLM interaction
    // -------------------------------------------------------------------------

    private fun isPatchable(diagnostics: List<BpmnDiagnostic>): Boolean {
        if (diagnostics.isEmpty() || diagnostics.size > PATCH_DIAGNOSTIC_LIMIT) return false
        return diagnostics.all { it.isPatchableByLabelFix() }
    }

    private fun BpmnDiagnostic.isPatchableByLabelFix(): Boolean =
        when (source) {
            BpmnDiagnosticSource.GRAPH -> message.contains("blank", ignoreCase = true)
                                       || message.contains("name", ignoreCase = true)
            BpmnDiagnosticSource.LINT  -> rule != null && PATCHABLE_LINT_RULES.any { rule.contains(it) }
            else -> false
        }

    private fun promptRunner(context: OperationContext, request: BpmnRequest): PromptRunner =
        config.repairer.promptRunner(context).withPromptContributor(request)

    private fun requestPatchCorrection(
        promptRunner: PromptRunner,
        messages: List<Message>,
        feedback: String,
    ): BpmnRepairPatch {
        val withFeedback = messages + UserMessage(feedback)
        return promptRunner.createObject(messages = withFeedback, outputClass = BpmnRepairPatch::class.java)
    }

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

    private fun normalizeXsdDiagnostics(
        rendered: RenderedBpmn,
        graph: LaidOutProcessGraph,
    ): List<BpmnDiagnostic> =
        bpmnXsdValidator.validateDetailed(rendered.xml).map { issue ->
            val elementId = issue.elementId?.takeIf { rendered.elementIndex.knownElementIds().contains(it) }
            scopedDiagnostic(
                graph = graph,
                diagnostic = BpmnDiagnostic(
                    source = BpmnDiagnosticSource.XSD,
                    message = issue.message,
                    elementId = elementId,
                    objectRef = rendered.elementIndex.objectRefForElementId(elementId),
                )
            )
        }

    private fun normalizeLintDiagnostics(
        lintIssues: List<LintIssue>,
        elementIndex: BpmnElementIndex,
        graph: LaidOutProcessGraph,
    ): List<BpmnDiagnostic> =
        lintIssues.map { issue ->
            val elementId = issue.id?.takeIf { elementIndex.knownElementIds().contains(it) }
            scopedDiagnostic(
                graph = graph,
                diagnostic = BpmnDiagnostic(
                    source = BpmnDiagnosticSource.LINT,
                    message = issue.message,
                    rule = issue.rule,
                    category = issue.category,
                    elementId = elementId,
                    objectRef = elementIndex.objectRefForElementId(elementId),
                )
            )
        }

    private fun logDiagnosticSummary(diagnostics: List<BpmnDiagnostic>) {
        logger.warn(
            "Diagnostic summary: total={}, graph={}, xsd={}, lint={}, scopes={}",
            diagnostics.size,
            diagnostics.count { it.source == BpmnDiagnosticSource.GRAPH },
            diagnostics.count { it.source == BpmnDiagnosticSource.XSD },
            diagnostics.count { it.source == BpmnDiagnosticSource.LINT },
            diagnostics.groupingBy { it.repairScope ?: BpmnRepairScope.FULL_PROCESS }.eachCount()
                .entries.joinToString(",") { "${it.key.name.lowercase()}=${it.value}" },
        )
        diagnostics.forEach { diagnostic ->
            logger.warn(
                "Diagnostic detail: source={}, rule={}, category={}, elementId={}, objectRef={}, repairScope={}, owner={}, message={}",
                diagnostic.source.name.lowercase(),
                diagnostic.rule ?: "-",
                diagnostic.category ?: "-",
                diagnostic.elementId ?: "-",
                diagnostic.objectRef ?: "-",
                diagnostic.repairScope?.name?.lowercase() ?: "-",
                diagnostic.ownerRef ?: "-",
                diagnostic.message,
            )
        }
    }

    private fun buildPatchFeedback(
        definition: BpmnDefinition,
        diagnostics: List<BpmnDiagnostic>,
    ): String = buildString {
        appendLine("The following diagnostics can be fixed with targeted name or label patches.")
        appendLine("Return a BpmnRepairPatch with the minimum operations needed to fix these issues.")
        appendLine("Do not rewrite the whole graph — only include operations that directly address the listed diagnostics.")
        appendLine()
        appendLine("Current canonical BpmnDefinition JSON:")
        appendLine(serializeDefinition(definition))
        appendLine()
        appendLine("Diagnostics to fix:")
        diagnostics.forEach { diagnostic ->
            appendLine("- ${formatDiagnostic(diagnostic)}")
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
        val scopes = diagnostics.mapNotNull { it.repairScope }.distinct()
        if (scopes.isNotEmpty()) {
            appendLine("Repair scope:")
            scopes.forEach { scope ->
                val owners = diagnostics.filter { it.repairScope == scope }.mapNotNull { it.ownerRef }.distinct()
                appendLine("- ${scope.name.lowercase()} owners=${owners.ifEmpty { listOf("unscoped") }.joinToString(",")}")
            }
            appendLine()
        }
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
        diagnostic.repairScope?.let { append(", repairScope=${it.name.lowercase()}") }
        diagnostic.ownerRef?.let { append(", owner=$it") }
        append(": ${diagnostic.message}")
    }

    private fun BpmnDiagnostic.isValidatorInfrastructureFailure(): Boolean {
        if (source != BpmnDiagnosticSource.LINT || rule != "parse-error") {
            return false
        }
        return VALIDATOR_INFRASTRUCTURE_MESSAGE_HINTS.any { message.contains(it, ignoreCase = true) }
    }

    private fun buildValidatorInfrastructureMessage(diagnostics: List<BpmnDiagnostic>): String =
        buildString {
            append("BPMN validator infrastructure failure")
            diagnostics.firstOrNull()?.message?.takeIf { it.isNotBlank() }?.let { append(": $it") }
            appendLine()
            appendLine("Non-repairable bpmn-lint diagnostic(s):")
            diagnostics.forEach { diagnostic ->
                appendLine("- ${formatDiagnostic(diagnostic)}")
            }
        }.trim()

    private fun lintRuleDocsPrompt(diagnostics: List<BpmnDiagnostic>): PromptContributor? {
        val lintRules = diagnostics
            .asSequence()
            .filter { it.source == BpmnDiagnosticSource.LINT }
            .mapNotNull { it.rule }
            .distinct()
            .toList()
        if (lintRules.isEmpty()) {
            return null
        }

        val docs = bpmnLintService.ruleDocs(lintRules)
        if (docs.isEmpty()) {
            return null
        }

        val content = buildString {
            appendLine("BPMNER lint rule documentation for current violations:")
            appendLine()
            docs.toSortedMap().forEach { (rule, markdown) ->
                appendLine("## $rule")
                appendLine()
                appendLine(markdown.trim())
                appendLine()
            }
        }.trim()

        return if (content.isBlank()) null else PromptContributor.fixed(content)
    }

    private fun renderFailureContext(attempt: BpmnRefinementAttempt): String = buildString {
        appendLine("<render failed>")
        attempt.renderFailureMessage?.let { appendLine(it) }
    }

    private fun serializeDefinition(definition: BpmnDefinition): String =
        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(definition)

    private fun scopedDiagnostic(
        graph: LaidOutProcessGraph,
        diagnostic: BpmnDiagnostic,
    ): BpmnDiagnostic {
        val ownerRef = diagnostic.ownerRef
            ?: graph.ownerForObjectRef(diagnostic.objectRef)
            ?: graph.ownerForElementId(diagnostic.elementId)
        val repairScope = diagnostic.repairScope ?: inferRepairScope(diagnostic, ownerRef)
        if (diagnostic.elementId != null && ownerRef == null && diagnostic.source != BpmnDiagnosticSource.RENDER) {
            logger.warn(
                "Ownership mapping ambiguous. source={}, elementId={}, objectRef={}, inferredRepairScope={}",
                diagnostic.source.name.lowercase(),
                diagnostic.elementId,
                diagnostic.objectRef ?: "-",
                repairScope.name.lowercase(),
            )
        }
        return diagnostic.copy(
            repairScope = repairScope,
            ownerRef = ownerRef,
        )
    }

    private fun inferRepairScope(
        diagnostic: BpmnDiagnostic,
        ownerRef: String?,
    ): BpmnRepairScope {
        if (diagnostic.elementId?.endsWith("_di") == true) {
            return BpmnRepairScope.LAYOUT
        }
        return when (diagnostic.source) {
            BpmnDiagnosticSource.RENDER -> {
                if (LAYOUT_HINTS.any { diagnostic.message.contains(it, ignoreCase = true) }) BpmnRepairScope.LAYOUT
                else BpmnRepairScope.FULL_PROCESS
            }
            BpmnDiagnosticSource.GRAPH -> {
                if (ownerRef != null || diagnostic.objectRef != null) BpmnRepairScope.PHASE
                else BpmnRepairScope.COMPOSITION
            }
            BpmnDiagnosticSource.XSD,
            BpmnDiagnosticSource.LINT,
            -> when {
                ownerRef != null -> BpmnRepairScope.PHASE
                diagnostic.objectRef == "process" -> BpmnRepairScope.COMPOSITION
                diagnostic.elementId != null -> BpmnRepairScope.COMPOSITION
                else -> BpmnRepairScope.FULL_PROCESS
            }
        }
    }

    private fun logArtifactsIfEnabled(definition: BpmnDefinition, rendered: RenderedBpmn?) {
        if (!config.logging.dumpArtifacts) {
            return
        }
        logger.debug("Artifact dump [definition]: {}", serializeDefinition(definition).truncate(config.logging.artifactPreviewLength))
        rendered?.let {
            logger.debug("Artifact dump [renderedXml]: {}", it.xml.truncate(config.logging.artifactPreviewLength))
        }
    }

    private fun String.truncate(maxLength: Int): String {
        if (length <= maxLength) {
            return this
        }
        val kept = substring(0, min(length, maxLength))
        return "$kept…<truncated>"
    }

    companion object {
        private const val PATCH_DIAGNOSTIC_LIMIT = 5
        private val PATCHABLE_LINT_RULES = listOf("label", "name", "naming")
        private val LAYOUT_HINTS = listOf("waypoint", "bounds", "diagram", "layout")
        private val VALIDATOR_INFRASTRUCTURE_MESSAGE_HINTS = listOf(
            "unknown rule",
            "Config resolution not supported",
            "resolveRule",
            "resolver",
            "bpmnlint-bundle",
            "bpmn-lint execution error",
        )
    }
}

class BpmnValidatorInfrastructureException(message: String) : IllegalStateException(message)

private data class BpmnRefinementAttempt(
    val definition: BpmnDefinition,
    val rendered: RenderedBpmn?,
    val diagnostics: List<BpmnDiagnostic>,
    val globalDiagnostics: GlobalDiagnostics,
    val validatedXml: String?,
    val messages: List<Message>,
    val renderFailureMessage: String? = null,
    val repairAttempts: Int = 0,
) {
    fun isSuccessful(): Boolean = validatedXml != null && diagnostics.isEmpty()

    fun toValidatedBpmnXml(): ValidatedBpmnXml = ValidatedBpmnXml(
        xml = validatedXml ?: error("No validated BPMN XML available"),
        diagnostics = diagnostics,
        repairAttempts = repairAttempts,
    )

    override fun toString(): String =
        "BpmnRefinementAttempt(successful=${isSuccessful()}, diagnostics=${diagnostics.size}, " +
        "repairAttempts=$repairAttempts, messages=${messages.size})"
}

private data class BpmnRefinementAttemptRecord(
    val attemptNumber: Int,
    val repairAttempts: Int,
    val graphDiagnostics: Int,
    val renderDiagnostics: Int,
    val xsdDiagnostics: Int,
    val lintDiagnostics: Int,
    val diagnosticFingerprint: String,
    val definitionFingerprint: String,
    val repairPromptFingerprint: String?,
) {
    fun compact(): String =
        "#$attemptNumber(repairs=$repairAttempts,graph=$graphDiagnostics,render=$renderDiagnostics," +
        "xsd=$xsdDiagnostics,lint=$lintDiagnostics,diag=$diagnosticFingerprint," +
        "def=$definitionFingerprint,prompt=${repairPromptFingerprint ?: "-"})"
}
