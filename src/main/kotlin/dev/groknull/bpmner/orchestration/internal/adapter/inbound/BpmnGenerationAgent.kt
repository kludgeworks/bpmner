/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.orchestration.internal.adapter.inbound

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.Export
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.domain.io.UserInput
import dev.groknull.bpmner.alignment.AlignedBpmnXml
import dev.groknull.bpmner.alignment.BpmnAligner
import dev.groknull.bpmner.contract.ProcessContractExtractor
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.BpmnRequestDraft
import dev.groknull.bpmner.core.BpmnRequestResolver
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.RenderedBpmn
import dev.groknull.bpmner.generation.BpmnGenerationStatus
import dev.groknull.bpmner.generation.BpmnProcessGenerator
import dev.groknull.bpmner.generation.BpmnRequestDrafter
import dev.groknull.bpmner.generation.BpmnResult
import dev.groknull.bpmner.generation.DefaultFlowAssigner
import dev.groknull.bpmner.generation.ValidatedOutline
import dev.groknull.bpmner.layout.BpmnLayoutPort
import dev.groknull.bpmner.layout.LayoutedBpmnXml
import dev.groknull.bpmner.readiness.BpmnReadinessInvoker
import dev.groknull.bpmner.readiness.ReadinessVerdict
import dev.groknull.bpmner.readiness.ReadyBpmnContext
import dev.groknull.bpmner.repair.BpmnRepairer
import dev.groknull.bpmner.validation.BpmnXsdValidationPort
import dev.groknull.bpmner.validation.FinalValidatedBpmnXml
import dev.groknull.bpmner.validation.ValidatedBpmnXml
import org.slf4j.LoggerFactory
import java.io.File

@Agent(description = "Single idiomatic agent for happy-path BPMN generation")
internal class BpmnGenerationAgent(
    private val requestDrafter: BpmnRequestDrafter,
    private val requestResolver: BpmnRequestResolver,
    private val readinessInvoker: BpmnReadinessInvoker,
    private val contractExtractor: ProcessContractExtractor,
    private val processGenerator: BpmnProcessGenerator,
    private val repairer: BpmnRepairer,
    private val layoutPort: BpmnLayoutPort,
    private val xsdValidationPort: BpmnXsdValidationPort,
    private val aligner: BpmnAligner,
    private val flowAssigner: DefaultFlowAssigner,
    private val config: BpmnConfig,
) {
    private val logger = LoggerFactory.getLogger(BpmnGenerationAgent::class.java)

    @Action
    fun draft(userInput: UserInput, ctx: OperationContext): BpmnRequestDraft {
        return requestDrafter.draftRequest(userInput, ctx)
    }

    @Action
    fun resolve(draft: BpmnRequestDraft): BpmnRequest {
        return requestResolver.resolveShellRequest(draft)
    }

    @Action
    fun assessReadiness(request: BpmnRequest): ReadyBpmnContext {
        val assessment = readinessInvoker.assess(request)
        require(assessment.verdict == ReadinessVerdict.READY) { "Not ready: ${assessment.verdict}" }
        return ReadyBpmnContext(request, assessment)
    }

    @Action
    fun extractContract(ready: ReadyBpmnContext, ctx: OperationContext): ValidatedProcessContract {
        return contractExtractor.extract(ready, ctx)
    }

    @Action
    fun createOutline(ready: ReadyBpmnContext, c: ValidatedProcessContract, ctx: OperationContext): ValidatedOutline {
        return processGenerator.createOutline(ready, c, ctx)
    }

    @Action fun composeGraph(outline: ValidatedOutline): LaidOutProcessGraph {
        return processGenerator.composeGraph(outline)
    }

    @Action fun render(ready: ReadyBpmnContext, graph: LaidOutProcessGraph): RenderedBpmn {
        return processGenerator.render(ready, graph)
    }

    @Action
    fun validate(
        ready: ReadyBpmnContext,
        g: LaidOutProcessGraph,
        r: RenderedBpmn,
        c: ValidatedProcessContract,
    ): ValidatedBpmnXml {
        return repairer.validateInitial(ready, g, r, c)
    }

    @Action
    fun layout(validated: ValidatedBpmnXml): FinalValidatedBpmnXml {
        val layoutedXml = layoutPort.layout(validated.xml)
        val layouted = LayoutedBpmnXml(definition = validated.definition, xml = layoutedXml)
        val xsdIssues = xsdValidationPort.validateDetailed(layouted.xml)
        if (xsdIssues.isNotEmpty()) {
            error("Auto-layout produced structurally invalid BPMN: " + xsdIssues.joinToString("; ") { it.message ?: "" })
        }
        return FinalValidatedBpmnXml(definition = layouted.definition, xml = layouted.xml)
    }

    @Action
    fun align(
        ready: ReadyBpmnContext,
        c: ValidatedProcessContract,
        x: FinalValidatedBpmnXml,
        ctx: OperationContext,
    ): AlignedBpmnXml {
        return aligner.align(ready, c, x, ctx)
    }

    @AchievesGoal(
        description = "Generate a complete BPMN definition from user input",
        export = Export(
            name = "generateBpmn",
            startingInputTypes = [UserInput::class, BpmnRequest::class],
        ),
    )
    @Action
    fun finish(
        ready: ReadyBpmnContext,
        aligned: AlignedBpmnXml,
    ): dev.groknull.bpmner.generation.BpmnResult {
        ready.request.outputFile?.let { File(it).writeText(aligned.xml, Charsets.UTF_8) }
        return BpmnResult(
            outputFile = ready.request.outputFile,
            status = BpmnGenerationStatus.GENERATED,
            xml = aligned.xml,
            alignmentReport = aligned.alignmentReport,
        )
    }
}
