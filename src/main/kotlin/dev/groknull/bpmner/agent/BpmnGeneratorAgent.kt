package dev.groknull.bpmner.agent

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.Export
import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.api.common.OperationContext
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import java.io.File

@Agent(description = "Generate a valid BPMN 2.0 diagram from a plain-language business process description")
class BpmnGeneratorAgent(
    private val config: BpmnConfig,
    private val bpmnConverter: BpmnDefinitionToXmlConverter,
    private val refinementWorkflow: BpmnRefinementWorkflow,
) {
    private val logger = LoggerFactory.getLogger(BpmnGeneratorAgent::class.java)
    private val objectMapper: ObjectMapper = jacksonObjectMapper().findAndRegisterModules()

    @Action(description = "Generate a typed BPMN definition from a business-process description")
    fun generateBpmnDefinition(request: BpmnRequest, context: OperationContext): BpmnDefinition {
        logger.info(
            "generateBpmnDefinition started. outputFile={}, actor={}",
            request.outputFile,
            config.generator.persona.name,
        )

        val promptRunner = promptRunner(context, request)
        val definition = promptRunner.createObject(request.generationPrompt(), BpmnDefinition::class.java)
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
        logger.debug(
            "renderBpmnXml started. processId={}, nodeCount={}, edgeCount={}",
            definition.processId,
            definition.nodes.size,
            definition.sequences.size,
        )
        val rendered = bpmnConverter.render(definition)
        logger.debug("renderBpmnXml completed. processId={}, xmlLength={}", definition.processId, rendered.xml.length)
        logger.debug("Rendered BPMN XML:\n{}", rendered.xml)
        return rendered
    }

    @Action(description = "Validate rendered BPMN, repair the typed definition if needed, and return validated BPMN XML")
    fun validateAndRefineBpmn(
        request: BpmnRequest,
        rendered: RenderedBpmn,
        context: ActionContext,
    ): ValidatedBpmnXml = refinementWorkflow.refine(request, rendered, context)

    @AchievesGoal(
        description = "Write validated BPMN 2.0 XML to the requested output file",
        export = Export(name = "generateBpmn", remote = true, startingInputTypes = [BpmnRequest::class]),
    )
    @Action(description = "Write the validated BPMN XML to disk")
    fun writeBpmn(request: BpmnRequest, bpmn: ValidatedBpmnXml): BpmnResult {
        logger.info("writeBpmn started. outputFile={}, xmlLength={}", request.outputFile, bpmn.xml.length)
        File(request.outputFile).writeText(bpmn.xml, Charsets.UTF_8)
        logger.info("BPMN written to {}", request.outputFile)
        return BpmnResult(outputFile = request.outputFile, xml = bpmn.xml)
    }

    private fun promptRunner(context: OperationContext, request: BpmnRequest) =
        config.generator.promptRunner(context).withPromptContributor(request)

    private fun serializeDefinition(definition: BpmnDefinition): String =
        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(definition)
}
