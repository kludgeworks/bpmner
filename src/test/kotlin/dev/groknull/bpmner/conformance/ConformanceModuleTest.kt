/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.conformance

import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnElementIndex
import dev.groknull.bpmner.bpmn.ComposedProcessGraph
import dev.groknull.bpmner.bpmn.LaidOutProcessGraph
import dev.groknull.bpmner.bpmn.OwnedElementGraph
import dev.groknull.bpmner.bpmn.RenderedBpmn
import dev.groknull.bpmner.conformance.internal.adapter.outbound.BpmnXsdValidator
import dev.groknull.bpmner.conformance.internal.domain.BpmnDefinitionValidator
import dev.groknull.bpmner.conformance.internal.domain.BpmnDiagnosticNormalizer
import dev.groknull.bpmner.conformance.internal.domain.BpmnEvaluationPipeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode
import org.springframework.test.context.TestPropertySource

/**
 * Validates that the `conformance` module context bootstraps and exposes its root-package ports.
 *
 * BootstrapMode.DIRECT_DEPENDENCIES (ADR-22 gate 4‴): `BpmnLoggingConfig` and `BpmnConformanceConfig`
 * are now owned by the `conformance` module itself (S4: `config` module dissolved). `@ConfigurationPropertiesScan`
 * in the app root supplies them; they materialise under isolation because they live in the `conformance`
 * module. `ConventionsLoader.bpmnerLintConfig` is guarded with `@ConditionalOnMissingBean` so no stub
 * is required. No platform agent is bootstrapped here. API keys are stubbed so no live LLM call is
 * made at startup. (S7 — ADR-22 Decisions 1; ARCHITECTURE §5 S7, G8; S4 — config dissolution)
 */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES, verifyAutomatically = false)
@TestPropertySource(
    properties = [
        "embabel.agent.platform.models.anthropic.api-key=test-key",
        "embabel.agent.platform.models.openai.api-key=test-key",
        "embabel.agent.platform.models.gemini.api-key=test-key",
        "embabel.agent.platform.models.mistralai.api-key=test-key",
        "embabel.agent.platform.models.deepseek.api-key=test-key",
    ],
)
class ConformanceModuleTest {
    @Autowired
    private lateinit var lintingPort: BpmnLintingPort

    @Autowired
    private lateinit var xsdValidationPort: BpmnXsdValidationPort

    private fun <T> anyNonNull(): T {
        org.mockito.ArgumentMatchers.any<T>()
        @Suppress("UNCHECKED_CAST")
        return null as T
    }

    @Test
    fun `conformance module bootstraps and exposes its ports`() {
        assertNotNull(lintingPort, "BpmnLintingPort should be available in the conformance module context")
        assertNotNull(xsdValidationPort, "BpmnXsdValidationPort should be available in the conformance module context")
    }

    @Test
    fun `BpmnXsdValidator validateDetailed unit test`() {
        val validator = BpmnXsdValidator()

        // 1. Valid XML
        val validXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         targetNamespace="http://bpmn.io/schema/bpmn"
                         id="Definitions_1">
              <process id="Process_1" isExecutable="false">
                <startEvent id="StartEvent_1"/>
              </process>
            </definitions>
        """.trimIndent()
        val validIssues = validator.validateDetailed(validXml)
        assertTrue(validIssues.isEmpty())

        // 2. Malformed XML (throws SAXException)
        val invalidIssues = validator.validateDetailed("<invalid/>")
        assertTrue(invalidIssues.isNotEmpty())
        assertTrue(invalidIssues[0].message.isNotEmpty())
    }

    private fun createPipelineAndContext(): PipelineTestContext {
        val config = BpmnLoggingConfig(dumpArtifacts = true, artifactPreviewLength = 5)
        val lintingPortMock = mock(BpmnLintingPort::class.java)
        val xsdValidationPortMock = mock(BpmnXsdValidationPort::class.java)
        val definitionValidator = mock(BpmnDefinitionValidator::class.java)
        val normalizer = mock(BpmnDiagnosticNormalizer::class.java)
        val fingerprints = mock(BpmnFingerprintService::class.java)

        val pipeline = BpmnEvaluationPipeline(
            config = config,
            bpmnLintingPort = lintingPortMock,
            bpmnXsdValidationPort = xsdValidationPortMock,
            bpmnDefinitionValidator = definitionValidator,
            normalizer = normalizer,
            fingerprints = fingerprints,
        )

        val definition = BpmnDefinition(
            processId = "Process_1",
            processName = "Test",
            nodes = emptyList(),
            sequences = emptyList(),
        )
        val graph = LaidOutProcessGraph(
            ownedGraph = OwnedElementGraph(
                composedGraph = ComposedProcessGraph(
                    definition = definition,
                    objectOwnersByObjectRef = emptyMap(),
                ),
                elementOwnersByElementId = emptyMap(),
                objectOwnersByObjectRef = emptyMap(),
            ),
            definition = definition,
        )

        `when`(fingerprints.serializeDefinition(anyNonNull())).thenReturn("serialized-definition-longer-string")

        return PipelineTestContext(
            pipeline = pipeline,
            definition = definition,
            graph = graph,
            lintingPortMock = lintingPortMock,
            xsdValidationPortMock = xsdValidationPortMock,
            definitionValidator = definitionValidator,
            normalizer = normalizer,
            fingerprints = fingerprints,
        )
    }

    private class PipelineTestContext(
        val pipeline: BpmnEvaluationPipeline,
        val definition: BpmnDefinition,
        val graph: LaidOutProcessGraph,
        val lintingPortMock: BpmnLintingPort,
        val xsdValidationPortMock: BpmnXsdValidationPort,
        val definitionValidator: BpmnDefinitionValidator,
        val normalizer: BpmnDiagnosticNormalizer,
        val fingerprints: BpmnFingerprintService,
    )

    @Test
    fun `BpmnEvaluationPipeline handles definition error`() {
        val ctx = createPipelineAndContext()
        `when`(ctx.definitionValidator.validate(ctx.definition)).thenReturn(listOf("Definition error"))
        `when`(ctx.fingerprints.serializeDefinition(ctx.definition)).thenReturn("def-long-serialized")
        val mockDiagnostic = BpmnDiagnostic(
            source = BpmnDiagnosticSource.GRAPH,
            message = "Definition error",
            repairScope = BpmnRepairScope.COMPOSITION,
        )
        `when`(ctx.normalizer.graphDiagnostic(ctx.graph, "Definition error")).thenReturn(mockDiagnostic)
        `when`(ctx.normalizer.infrastructureDiagnostics(listOf(mockDiagnostic))).thenReturn(emptyList())

        val result = ctx.pipeline.evaluate(ctx.graph, ctx.definition, null, null, 0)
        assertEquals(1, result.diagnostics.size)
        assertEquals(BpmnDiagnosticSource.GRAPH, result.diagnostics[0].source)
    }

    @Test
    fun `BpmnEvaluationPipeline handles render failure`() {
        val ctx = createPipelineAndContext()
        `when`(ctx.definitionValidator.validate(ctx.definition)).thenReturn(emptyList())
        val renderDiagnostic = BpmnDiagnostic(
            source = BpmnDiagnosticSource.RENDER,
            message = "Failed to render",
        )
        `when`(
            ctx.normalizer.scopedDiagnostic(
                ctx.graph,
                BpmnDiagnostic(source = BpmnDiagnosticSource.RENDER, message = "Failed to render"),
            ),
        ).thenReturn(renderDiagnostic)
        `when`(ctx.normalizer.infrastructureDiagnostics(listOf(renderDiagnostic))).thenReturn(emptyList())

        val result = ctx.pipeline.evaluate(ctx.graph, ctx.definition, null, "Failed to render", 1)
        assertEquals(1, result.diagnostics.size)
        assertEquals(BpmnDiagnosticSource.RENDER, result.diagnostics[0].source)
    }

    @Test
    fun `BpmnEvaluationPipeline handles XSD validation failure`() {
        val ctx = createPipelineAndContext()
        val index = BpmnElementIndex(
            processId = "Process_1",
            nodeObjectRefs = emptyMap(),
            edgeObjectRefs = emptyMap(),
        )
        val rendered = RenderedBpmn(ctx.definition, "<xml/>", index)
        val xsdIssue = XsdValidationIssue("XSD error")
        val xsdDiagnostic = BpmnDiagnostic(source = BpmnDiagnosticSource.XSD, message = "XSD error")

        `when`(ctx.xsdValidationPortMock.validateDetailed(rendered.xml)).thenReturn(listOf(xsdIssue))
        `when`(
            ctx.normalizer.normalizeXsdDiagnostics(listOf(xsdIssue), rendered, ctx.graph),
        ).thenReturn(listOf(xsdDiagnostic))
        `when`(ctx.normalizer.infrastructureDiagnostics(listOf(xsdDiagnostic))).thenReturn(emptyList())

        val result = ctx.pipeline.evaluate(ctx.graph, ctx.definition, rendered, null, 1)
        assertEquals(1, result.diagnostics.size)
        assertEquals(BpmnDiagnosticSource.XSD, result.diagnostics[0].source)
    }

    @Test
    fun `BpmnEvaluationPipeline handles linting available`() {
        val ctx = createPipelineAndContext()
        val index = BpmnElementIndex(
            processId = "Process_1",
            nodeObjectRefs = emptyMap(),
            edgeObjectRefs = emptyMap(),
        )
        val rendered = RenderedBpmn(ctx.definition, "<xml/>", index)
        val lintIssue = LintIssue(id = null, rule = "rule1", message = "lint error")
        val lintDiagnostic = BpmnDiagnostic(source = BpmnDiagnosticSource.LINT, message = "lint error")

        `when`(ctx.xsdValidationPortMock.validateDetailed(rendered.xml)).thenReturn(emptyList())
        `when`(
            ctx.normalizer.normalizeXsdDiagnostics(emptyList(), rendered, ctx.graph),
        ).thenReturn(emptyList())
        `when`(ctx.lintingPortMock.lint(ctx.definition)).thenReturn(listOf(lintIssue))
        `when`(
            ctx.normalizer.normalizeLintDiagnostics(listOf(lintIssue), rendered.elementIndex, ctx.graph),
        ).thenReturn(listOf(lintDiagnostic))
        `when`(ctx.normalizer.infrastructureDiagnostics(listOf(lintDiagnostic))).thenReturn(emptyList())

        val result = ctx.pipeline.evaluate(ctx.graph, ctx.definition, rendered, null, 1)
        assertEquals(1, result.diagnostics.size)
        assertEquals(BpmnDiagnosticSource.LINT, result.diagnostics[0].source)
    }

    @Test
    fun `BpmnEvaluationPipeline handles linting unavailable`() {
        val ctx = createPipelineAndContext()
        val index = BpmnElementIndex(
            processId = "Process_1",
            nodeObjectRefs = emptyMap(),
            edgeObjectRefs = emptyMap(),
        )
        val rendered = RenderedBpmn(ctx.definition, "<xml/>", index)

        `when`(ctx.xsdValidationPortMock.validateDetailed(rendered.xml)).thenReturn(emptyList())
        `when`(
            ctx.normalizer.normalizeXsdDiagnostics(emptyList(), rendered, ctx.graph),
        ).thenReturn(emptyList())
        `when`(ctx.lintingPortMock.lint(ctx.definition)).thenReturn(null)
        `when`(ctx.normalizer.infrastructureDiagnostics(emptyList())).thenReturn(emptyList())

        val result = ctx.pipeline.evaluate(ctx.graph, ctx.definition, rendered, null, 1)
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun `BpmnEvaluationPipeline throws infrastructure exception`() {
        val ctx = createPipelineAndContext()
        val infraDiagnostic = BpmnDiagnostic(
            source = BpmnDiagnosticSource.LINT,
            rule = "parse-error",
            message = "bpmnlint-bundle load failed",
        )
        `when`(ctx.definitionValidator.validate(anyNonNull())).thenReturn(listOf("Infra error"))
        `when`(
            ctx.normalizer.graphDiagnostic(anyNonNull(), anyNonNull()),
        ).thenReturn(infraDiagnostic)
        `when`(ctx.normalizer.infrastructureDiagnostics(anyNonNull())).thenReturn(listOf(infraDiagnostic))
        `when`(
            ctx.normalizer.validatorInfrastructureMessage(anyNonNull()),
        ).thenReturn("BPMN validator infrastructure failure")

        assertThrows(BpmnValidatorInfrastructureException::class.java) {
            ctx.pipeline.evaluate(ctx.graph, ctx.definition, null, null, 0)
        }
    }
}
