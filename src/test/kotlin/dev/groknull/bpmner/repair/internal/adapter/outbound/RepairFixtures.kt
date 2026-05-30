/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.adapter.outbound

import com.embabel.common.textio.template.JinjavaTemplateRenderer
import dev.groknull.bpmner.api.RepairKind
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnElementIndex
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnUserTask
import dev.groknull.bpmner.core.ComposedProcessGraph
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.OwnedElementGraph
import dev.groknull.bpmner.core.RenderedBpmn
import dev.groknull.bpmner.repair.BpmnRepairAttempt
import dev.groknull.bpmner.validation.BpmnAutoFixResult
import dev.groknull.bpmner.validation.BpmnDiagnostic
import dev.groknull.bpmner.validation.BpmnDiagnosticSeverity
import dev.groknull.bpmner.validation.BpmnDiagnosticSource
import dev.groknull.bpmner.validation.BpmnEvaluation
import dev.groknull.bpmner.validation.BpmnFingerprintService
import dev.groknull.bpmner.validation.BpmnLintRuleCapability
import dev.groknull.bpmner.validation.BpmnLintingPort
import dev.groknull.bpmner.validation.BpmnRepairScope
import dev.groknull.bpmner.validation.BpmnRuleGuidancePort
import dev.groknull.bpmner.validation.GlobalDiagnostics
import dev.groknull.bpmner.validation.LintIssue

/**
 * Canonical inputs + factory wiring shared between [BpmnRepairPromptFactoryTest], the
 * repair-template probes in [dev.groknull.bpmner.prompt.PromptSizeProbeTest], and the
 * `update_prompt_baselines` binary.
 */
internal object RepairFixtures {
    // Cache one factory per JVM: BpmnRepairPromptFactory + JinjavaTemplateRenderer +
    // BpmnFingerprintService are all stateless (the latter holds only an immutable
    // ObjectMapper). Tests across this JVM share the instance, saving the non-trivial
    // Jinjava init cost per call.
    private val factoryInstance: BpmnRepairPromptFactory = BpmnRepairPromptFactory(
        NoopLintingPort,
        BpmnFingerprintService(),
        NoopRuleGuidancePort,
        JinjavaTemplateRenderer(),
    )

    fun factory(): BpmnRepairPromptFactory = factoryInstance

    fun sampleDefinition(): BpmnDefinition = BpmnDefinition(
        processId = "Process_1",
        processName = "Sample",
        nodes =
        listOf(
            BpmnStartEvent("Start_1", "Start"),
            BpmnUserTask("Task_1", "Do thing"),
            BpmnUserTask("Task_2", "Do other"),
            BpmnEndEvent("End_1", "End"),
        ),
        sequences =
        listOf(
            BpmnEdge("Flow_1", "Start_1", "Task_1"),
            BpmnEdge("Flow_2", "Task_1", "Task_2"),
            BpmnEdge("Flow_3", "Task_2", "End_1"),
        ),
    )

    fun lintDiagnostic(
        rule: String,
        elementId: String?,
        message: String,
        kind: RepairKind,
        severity: BpmnDiagnosticSeverity = BpmnDiagnosticSeverity.ERROR,
    ): BpmnDiagnostic = BpmnDiagnostic(
        source = BpmnDiagnosticSource.LINT,
        message = message,
        rule = rule,
        severity = severity,
        elementId = elementId,
        kind = kind,
        repairScope = BpmnRepairScope.PHASE,
    )

    fun attempt(
        definition: BpmnDefinition,
        diagnostics: List<BpmnDiagnostic>,
    ): BpmnRepairAttempt {
        val rendered =
            RenderedBpmn(
                definition = definition,
                xml = "<bpmn/>",
                elementIndex =
                BpmnElementIndex(
                    processId = definition.processId,
                    nodeObjectRefs = definition.nodes.associate { it.id to "nodes[id=${it.id}]" },
                    edgeObjectRefs = definition.sequences.associate { it.id to "sequences[id=${it.id}]" },
                ),
            )
        val evaluation =
            BpmnEvaluation(
                definition = definition,
                rendered = rendered,
                diagnostics = diagnostics,
                globalDiagnostics = GlobalDiagnostics(diagnostics),
                validatedXml = null,
            )
        val owner = "phase:main"
        val objectOwners =
            buildMap {
                put("process", owner)
                definition.nodes.forEach { put("nodes[id=${it.id}]", owner) }
                definition.sequences.forEach { put("sequences[id=${it.id}]", owner) }
            }
        val composed =
            ComposedProcessGraph(
                definition = definition,
                objectOwnersByObjectRef = objectOwners,
            )
        val elementOwners =
            buildMap {
                put(definition.processId, owner)
                definition.nodes.forEach {
                    put(it.id, owner)
                    put("${it.id}_di", owner)
                }
                definition.sequences.forEach {
                    put(it.id, owner)
                    put("${it.id}_di", owner)
                }
            }
        val graph = LaidOutProcessGraph(OwnedElementGraph(composed, elementOwners, objectOwners), definition)
        return BpmnRepairAttempt(
            attemptNumber = 1,
            repairAttempts = 0,
            graph = graph,
            evaluation = evaluation,
            messages = emptyList(),
        )
    }

    /**
     * Canonical diagnostic used by the ratchet probes — single ERROR-severity LLM_MODEL_PATCH
     * naming-rule violation. Shape matches realistic repair inputs without being so elaborate
     * that template-size growth from the diagnostic itself dominates the measurement.
     */
    fun canonicalDiagnostic(): BpmnDiagnostic = lintDiagnostic(
        rule = "bpmner/name-02",
        elementId = "Task_1",
        message = "Use action verb",
        kind = RepairKind.LLM_MODEL_PATCH,
    )

    /**
     * Render the `bpmner/repair/patch_feedback` template with canonical inputs.
     */
    fun renderPatchFeedback(): String = factory().patchFeedback(
        sampleDefinition(),
        listOf(canonicalDiagnostic()),
    )

    /**
     * Render the `bpmner/repair/full_feedback` template with canonical inputs.
     */
    fun renderFullFeedback(): String {
        val definition = sampleDefinition()
        return factory().fullRepairFeedback(attempt(definition, emptyList()), listOf(canonicalDiagnostic()))
    }

    private object NoopLintingPort : BpmnLintingPort {
        override fun lint(definition: BpmnDefinition): List<LintIssue> = emptyList()

        override fun autoFix(
            bpmnXml: String,
            issues: List<LintIssue>,
        ): BpmnAutoFixResult? = null

        override fun ruleDocs(ruleNames: Collection<String>): Map<String, String> = emptyMap()

        override fun lintRuleCapabilities(): Map<String, BpmnLintRuleCapability> = emptyMap()
    }

    private object NoopRuleGuidancePort : BpmnRuleGuidancePort {
        override fun getLlmRuleGuidance(): String = ""
    }
}
