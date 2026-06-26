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
import dev.groknull.bpmner.bpmn.RepairKind
import dev.groknull.bpmner.bpmn.RepairSafety
import dev.groknull.bpmner.conformance.internal.domain.BpmnDiagnosticNormalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BpmnDiagnosticNormalizerTest {
    private val localXmlCapability =
        BpmnLintRuleCapability(
            id = "gtw-converging-gateway-unnamed",
            kind = RepairKind.LOCAL_XML_FIX,
            repairSafety = RepairSafety.SAFE_AUTOMATIC,
            fixHandler = "clearGatewayName",
            handlerExists = true,
            replacementMap = null,
        )
    private val llmCapability =
        BpmnLintRuleCapability(
            id = "act-verb-object-name",
            kind = RepairKind.LLM_MODEL_PATCH,
            repairSafety = RepairSafety.LLM_ONLY,
            fixHandler = null,
            handlerExists = false,
            replacementMap = null,
        )

    private val stubPort =
        object : BpmnLintingPort {
            override fun lint(definition: BpmnDefinition): List<LintIssue>? = emptyList()

            override fun autoFix(
                bpmnXml: String,
                issues: List<LintIssue>,
            ): BpmnAutoFixResult? = null

            override fun ruleDocs(ruleNames: Collection<String>): Map<String, String> = emptyMap()

            override fun lintRuleCapabilities(): Map<String, BpmnLintRuleCapability> = mapOf(
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

    @Test
    fun `inferRepairScope for RENDER source returns FULL_PROCESS`() {
        val diag = BpmnDiagnostic(source = BpmnDiagnosticSource.RENDER, message = "render err")
        val normalized = normalizer.scopedDiagnostic(emptyGraph, diag)
        assertEquals(BpmnRepairScope.FULL_PROCESS, normalized.repairScope)
    }

    @Test
    fun `inferRepairScope for GRAPH source with ownerRef returns PHASE`() {
        val diag = BpmnDiagnostic(source = BpmnDiagnosticSource.GRAPH, message = "graph err", ownerRef = "owner1")
        val normalized = normalizer.scopedDiagnostic(emptyGraph, diag)
        assertEquals(BpmnRepairScope.PHASE, normalized.repairScope)
    }

    @Test
    fun `inferRepairScope for GRAPH source with objectRef returns PHASE`() {
        val diag = BpmnDiagnostic(source = BpmnDiagnosticSource.GRAPH, message = "graph err", objectRef = "someRef")
        val normalized = normalizer.scopedDiagnostic(emptyGraph, diag)
        assertEquals(BpmnRepairScope.PHASE, normalized.repairScope)
    }

    @Test
    fun `inferRepairScope for GRAPH source with no owner or objectRef returns COMPOSITION`() {
        val diag = BpmnDiagnostic(source = BpmnDiagnosticSource.GRAPH, message = "graph err")
        val normalized = normalizer.scopedDiagnostic(emptyGraph, diag)
        assertEquals(BpmnRepairScope.COMPOSITION, normalized.repairScope)
    }

    @Test
    fun `inferRepairScope for XSD and LINT sources`() {
        // XSD/LINT with ownerRef -> PHASE
        val diag1 = BpmnDiagnostic(source = BpmnDiagnosticSource.XSD, message = "xsd err", ownerRef = "owner1")
        assertEquals(BpmnRepairScope.PHASE, normalizer.scopedDiagnostic(emptyGraph, diag1).repairScope)

        // XSD/LINT with objectRef == "process" -> COMPOSITION
        val diag2 = BpmnDiagnostic(source = BpmnDiagnosticSource.LINT, message = "lint err", objectRef = "process")
        assertEquals(BpmnRepairScope.COMPOSITION, normalizer.scopedDiagnostic(emptyGraph, diag2).repairScope)

        // XSD/LINT with elementId != null -> COMPOSITION
        val diag3 = BpmnDiagnostic(source = BpmnDiagnosticSource.XSD, message = "xsd err", elementId = "someId")
        assertEquals(BpmnRepairScope.COMPOSITION, normalizer.scopedDiagnostic(emptyGraph, diag3).repairScope)

        // XSD/LINT with other values -> FULL_PROCESS
        val diag4 = BpmnDiagnostic(source = BpmnDiagnosticSource.LINT, message = "lint err")
        assertEquals(BpmnRepairScope.FULL_PROCESS, normalizer.scopedDiagnostic(emptyGraph, diag4).repairScope)
    }

    @Test
    fun `infrastructureDiagnostics filters correctly`() {
        val infraDiag = BpmnDiagnostic(
            source = BpmnDiagnosticSource.LINT,
            rule = "parse-error",
            message = "unknown rule foo",
        )
        val nonInfraDiag = BpmnDiagnostic(
            source = BpmnDiagnosticSource.LINT,
            rule = "some-other-rule",
            message = "some error",
        )
        val list = normalizer.infrastructureDiagnostics(listOf(infraDiag, nonInfraDiag))
        assertEquals(1, list.size)
        assertEquals("unknown rule foo", list[0].message)
    }

    @Test
    fun `validatorInfrastructureMessage formats message properly`() {
        val infraDiag = BpmnDiagnostic(
            source = BpmnDiagnosticSource.LINT,
            rule = "parse-error",
            message = "unknown rule foo",
        )
        val msg = normalizer.validatorInfrastructureMessage(listOf(infraDiag))
        assertTrue(msg.contains("BPMN validator infrastructure failure"))
        assertTrue(msg.contains("unknown rule foo"))
        assertTrue(msg.contains("Non-repairable bpmn-lint diagnostic(s):"))

        val emptyMsg = normalizer.validatorInfrastructureMessage(emptyList())
        assertEquals("BPMN validator infrastructure failure\nNon-repairable bpmn-lint diagnostic(s):", emptyMsg)
    }

    @Test
    fun `isValidatorInfrastructureFailure matches hints`() {
        val base = BpmnDiagnostic(source = BpmnDiagnosticSource.LINT, rule = "parse-error", message = "")

        fun check(msg: String, expected: Boolean) {
            val list = normalizer.infrastructureDiagnostics(listOf(base.copy(message = msg)))
            assertEquals(expected, list.isNotEmpty())
        }

        check("An unknown rule appeared", true)
        check("Config resolution not supported here", true)
        check("Failed to resolveRule", true)
        check("The resolver failed", true)
        check("Cannot load bpmnlint-bundle", true)
        check("BPMN-LINT execution error occurred", true)

        // Non-matching message
        check("Just a syntax error", false)

        // Non-matching source
        val xsdDiag = base.copy(source = BpmnDiagnosticSource.XSD, message = "unknown rule")
        assertTrue(normalizer.infrastructureDiagnostics(listOf(xsdDiag)).isEmpty())

        // Non-matching rule
        val ruleDiag = base.copy(rule = "other", message = "unknown rule")
        assertTrue(normalizer.infrastructureDiagnostics(listOf(ruleDiag)).isEmpty())
    }

    private fun mockRendered(): RenderedBpmn = RenderedBpmn(
        definition = emptyDefinition,
        xml = "<empty/>",
        elementIndex = emptyIndex,
    )
}
