/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.validation.internal.domain

import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnElementIndex
import dev.groknull.bpmner.core.ComposedProcessGraph
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.OwnedElementGraph
import dev.groknull.bpmner.core.RenderedBpmn
import dev.groknull.bpmner.validation.BpmnAutoFixResult
import dev.groknull.bpmner.validation.BpmnDiagnosticSource
import dev.groknull.bpmner.validation.BpmnLintRuleCapability
import dev.groknull.bpmner.validation.BpmnLintingPort
import dev.groknull.bpmner.validation.BpmnRepairSafety
import dev.groknull.bpmner.validation.LintIssue
import dev.groknull.bpmner.validation.RepairKind
import dev.groknull.bpmner.validation.XsdValidationIssue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BpmnDiagnosticNormalizerTest {
    private val localXmlCapability =
        BpmnLintRuleCapability(
            id = "gtw-converging-gateway-unnamed",
            kind = RepairKind.LOCAL_XML_FIX,
            repairSafety = BpmnRepairSafety.SAFE_AUTOMATIC,
            fixHandler = "clearGatewayName",
            handlerExists = true,
            replacementMap = null,
        )
    private val llmCapability =
        BpmnLintRuleCapability(
            id = "act-verb-object-name",
            kind = RepairKind.LLM_MODEL_PATCH,
            repairSafety = BpmnRepairSafety.LLM_ONLY,
            fixHandler = null,
            handlerExists = false,
            replacementMap = null,
        )

    private val stubPort =
        object : BpmnLintingPort {
            override fun lint(bpmnXml: String): List<LintIssue>? = emptyList()

            override fun autoFix(
                bpmnXml: String,
                issues: List<LintIssue>,
            ): BpmnAutoFixResult? = null

            override fun ruleDocs(ruleNames: Collection<String>): Map<String, String> = emptyMap()

            override fun lintRuleCapabilities(): Map<String, BpmnLintRuleCapability> =
                mapOf(
                    localXmlCapability.id to localXmlCapability,
                    llmCapability.id to llmCapability,
                )
        }

    private val normalizer = BpmnDiagnosticNormalizer(stubPort)

    private val emptyIndex =
        BpmnElementIndex(
            processId = "Process_1",
            nodeObjectRefs = emptyMap(),
            edgeObjectRefs = emptyMap(),
        )

    private val emptyDefinition =
        BpmnDefinition(
            processId = "Process_1",
            processName = "Test",
            nodes = emptyList(),
            sequences = emptyList(),
        )

    private val emptyGraph =
        LaidOutProcessGraph(
            ownedGraph =
                OwnedElementGraph(
                    composedGraph =
                        ComposedProcessGraph(
                            definition = emptyDefinition,
                            objectOwnersByObjectRef = emptyMap(),
                        ),
                    elementOwnersByElementId = emptyMap(),
                    objectOwnersByObjectRef = emptyMap(),
                ),
            definition = emptyDefinition,
        )

    @Test
    fun `normalizeLintDiagnostics stamps LOCAL_XML_FIX kind for known rule`() {
        val issues = listOf(LintIssue(id = null, rule = "bpmner/gtw-converging-gateway-unnamed", message = "named converging"))

        val diagnostics = normalizer.normalizeLintDiagnostics(issues, emptyIndex, emptyGraph)

        assertEquals(1, diagnostics.size)
        assertEquals(RepairKind.LOCAL_XML_FIX, diagnostics[0].kind)
        assertEquals("clearGatewayName", diagnostics[0].fixHandler)
    }

    @Test
    fun `normalizeLintDiagnostics stamps LLM_MODEL_PATCH kind for known LLM rule`() {
        val issues = listOf(LintIssue(id = null, rule = "bpmner/act-verb-object-name", message = "bad label"))

        val diagnostics = normalizer.normalizeLintDiagnostics(issues, emptyIndex, emptyGraph)

        assertEquals(RepairKind.LLM_MODEL_PATCH, diagnostics[0].kind)
        assertNull(diagnostics[0].fixHandler)
    }

    @Test
    fun `normalizeLintDiagnostics falls back to LLM_MODEL_PATCH for unknown rule`() {
        val issues = listOf(LintIssue(id = null, rule = "bpmner/some-unknown-rule", message = "unknown"))

        val diagnostics = normalizer.normalizeLintDiagnostics(issues, emptyIndex, emptyGraph)

        assertEquals(RepairKind.LLM_MODEL_PATCH, diagnostics[0].kind)
        assertNull(diagnostics[0].fixHandler)
        assertNull(diagnostics[0].repairSafety)
    }

    @Test
    fun `normalizeXsdDiagnostics does not stamp kind`() {
        val issues = listOf(XsdValidationIssue(message = "xsd error", elementId = null))

        val diagnostics = normalizer.normalizeXsdDiagnostics(issues, mockRendered(), emptyGraph)

        assertEquals(BpmnDiagnosticSource.XSD, diagnostics[0].source)
        assertNull(diagnostics[0].kind)
    }

    @Test
    fun `graphDiagnostic does not stamp kind`() {
        val diagnostic = normalizer.graphDiagnostic(emptyGraph, "graph error")

        assertEquals(BpmnDiagnosticSource.GRAPH, diagnostic.source)
        assertNull(diagnostic.kind)
    }

    @Test
    fun `bpmnlint-plugin-bpmner prefix is stripped for lookup`() {
        val issues =
            listOf(
                LintIssue(
                    id = null,
                    rule = "bpmnlint-plugin-bpmner/gtw-converging-gateway-unnamed",
                    message = "named",
                ),
            )

        val diagnostics = normalizer.normalizeLintDiagnostics(issues, emptyIndex, emptyGraph)

        assertEquals(RepairKind.LOCAL_XML_FIX, diagnostics[0].kind)
    }

    private fun mockRendered(): RenderedBpmn =
        RenderedBpmn(
            definition = emptyDefinition,
            xml = "<empty/>",
            elementIndex = emptyIndex,
        )
}
