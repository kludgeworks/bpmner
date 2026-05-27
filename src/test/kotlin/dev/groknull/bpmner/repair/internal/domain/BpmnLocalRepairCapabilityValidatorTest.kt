/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain

import dev.groknull.bpmner.api.RepairKind
import dev.groknull.bpmner.api.RepairSafety
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.validation.BpmnAutoFixResult
import dev.groknull.bpmner.validation.BpmnLintRuleCapability
import dev.groknull.bpmner.validation.BpmnLintingPort
import dev.groknull.bpmner.validation.LintIssue
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BpmnLocalRepairCapabilityValidatorTest {
    @Test
    fun `validate passes when all LOCAL_MODEL_FIX handlers are bound`() {
        val caps =
            mapOf(
                "topo-01" to cap("topo-01", RepairKind.LOCAL_MODEL_FIX, fixHandler = "splitJoinForkGateway"),
                "name-01" to cap("name-01", RepairKind.LOCAL_MODEL_FIX, fixHandler = "stripTypeWords"),
                "llm-01" to cap("llm-01", RepairKind.LLM_MODEL_PATCH, fixHandler = null),
                "unfix-01" to cap("unfix-01", RepairKind.UNFIXABLE, fixHandler = null),
            )
        val validator = validatorWith(handlers = setOf("splitJoinForkGateway", "stripTypeWords"))
        validator.validate(caps, setOf("splitJoinForkGateway", "stripTypeWords"))
    }

    @Test
    fun `validate fails when LOCAL_MODEL_FIX handler is missing from Kotlin registry`() {
        val caps = mapOf("topo-01" to cap("topo-01", RepairKind.LOCAL_MODEL_FIX, fixHandler = "missingHandler"))
        val validator = validatorWith(handlers = emptySet())
        val error =
            assertFailsWith<BpmnRepairCapabilityValidationException> {
                validator.validate(caps, emptySet())
            }
        assertTrue(error.message!!.contains("missingHandler"))
        assertTrue(error.message!!.contains("LOCAL_MODEL_FIX"))
    }

    @Test
    fun `validate fails when LOCAL_MODEL_FIX rule has no handler declared`() {
        val caps = mapOf("topo-01" to cap("topo-01", RepairKind.LOCAL_MODEL_FIX, fixHandler = null))
        val validator = validatorWith(handlers = emptySet())
        val error =
            assertFailsWith<BpmnRepairCapabilityValidationException> {
                validator.validate(caps, emptySet())
            }
        assertTrue(error.message!!.contains("topo-01"))
    }

    @Test
    fun `validate ignores LLM and UNFIXABLE kinds`() {
        val caps =
            mapOf(
                "llm-01" to cap("llm-01", RepairKind.LLM_MODEL_PATCH, fixHandler = null),
                "llm-02" to cap("llm-02", RepairKind.LLM_XML_REWRITE, fixHandler = null),
                "unfix-01" to cap("unfix-01", RepairKind.UNFIXABLE, fixHandler = null),
            )
        val validator = validatorWith(handlers = emptySet())
        validator.validate(caps, emptySet())
    }

    @Test
    fun `validate ignores stale LOCAL_XML_FIX capabilities after the 2F collapse`() {
        // LOCAL_XML_FIX is deprecated; the validator no longer enforces a TS handler registry.
        // Any stale LOCAL_XML_FIX cap that sneaks in from an external catalog is silently skipped.
        @Suppress("DEPRECATION")
        val staleCap = cap("legacy-xml", RepairKind.LOCAL_XML_FIX, fixHandler = "nonExistentTsHandler")
        val validator = validatorWith(handlers = emptySet())
        validator.validate(mapOf("legacy-xml" to staleCap), emptySet())
    }

    private fun validatorWith(handlers: Set<String>): BpmnLocalRepairCapabilityValidator {
        val handlerInstances = handlers.map { stubHandler(it) }
        return BpmnLocalRepairCapabilityValidator(
            lintingPort = NoopLintingPort,
            modelFixHandlerRegistry = BpmnLocalModelFixHandlerRegistry(handlerInstances),
        )
    }

    private fun stubHandler(name: String): BpmnLocalModelFixHandler = object : BpmnLocalModelFixHandler {
        override val handlerName: String = name

        override fun buildPatch(
            definition: BpmnDefinition,
            elementId: String,
            config: HandlerConfig,
        ): List<BpmnPatchOperation> = emptyList()
    }

    private fun cap(
        id: String,
        kind: RepairKind,
        fixHandler: String?,
    ) = BpmnLintRuleCapability(
        id = id,
        kind = kind,
        repairSafety = RepairSafety.SAFE_AUTOMATIC,
        fixHandler = fixHandler,
        handlerExists = kind == RepairKind.LOCAL_MODEL_FIX || kind == RepairKind.LOCAL_XML_FIX,
        replacementMap = null,
    )

    private object NoopLintingPort : BpmnLintingPort {
        override fun lint(definition: BpmnDefinition): List<LintIssue> = emptyList()

        override fun autoFix(
            bpmnXml: String,
            issues: List<LintIssue>,
        ): BpmnAutoFixResult? = null

        override fun ruleDocs(ruleNames: Collection<String>): Map<String, String> = emptyMap()

        override fun lintRuleCapabilities(): Map<String, BpmnLintRuleCapability> = emptyMap()
    }
}
