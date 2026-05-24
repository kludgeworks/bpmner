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
    fun `validate passes when all handlers are bound`() {
        val caps =
            mapOf(
                "name-01" to cap("name-01", RepairKind.LOCAL_XML_FIX, fixHandler = "stripTypeWords"),
                "topo-01" to cap("topo-01", RepairKind.LOCAL_MODEL_FIX, fixHandler = "splitJoinForkGateway"),
                "llm-01" to cap("llm-01", RepairKind.LLM_MODEL_PATCH, fixHandler = null),
                "unfix-01" to cap("unfix-01", RepairKind.UNFIXABLE, fixHandler = null),
            )
        val validator = validatorWith(handlers = setOf("splitJoinForkGateway"))
        validator.validate(caps, setOf("splitJoinForkGateway"), setOf("stripTypeWords"))
    }

    @Test
    fun `validate fails when LOCAL_MODEL_FIX handler is missing from Kotlin registry`() {
        val caps = mapOf("topo-01" to cap("topo-01", RepairKind.LOCAL_MODEL_FIX, fixHandler = "missingHandler"))
        val validator = validatorWith(handlers = emptySet())
        val error =
            assertFailsWith<BpmnRepairCapabilityValidationException> {
                validator.validate(caps, emptySet(), emptySet())
            }
        assertTrue(error.message!!.contains("missingHandler"))
        assertTrue(error.message!!.contains("LOCAL_MODEL_FIX"))
    }

    @Test
    fun `validate fails when LOCAL_XML_FIX handler is missing from TS registry`() {
        val caps = mapOf("name-01" to cap("name-01", RepairKind.LOCAL_XML_FIX, fixHandler = "missingTs"))
        val validator = validatorWith(handlers = emptySet())
        val error =
            assertFailsWith<BpmnRepairCapabilityValidationException> {
                validator.validate(caps, emptySet(), emptySet())
            }
        assertTrue(error.message!!.contains("missingTs"))
        assertTrue(error.message!!.contains("LOCAL_XML_FIX"))
    }

    @Test
    fun `validate fails when LOCAL_MODEL_FIX rule has no handler declared`() {
        val caps = mapOf("topo-01" to cap("topo-01", RepairKind.LOCAL_MODEL_FIX, fixHandler = null))
        val validator = validatorWith(handlers = emptySet())
        val error =
            assertFailsWith<BpmnRepairCapabilityValidationException> {
                validator.validate(caps, emptySet(), emptySet())
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
        validator.validate(caps, emptySet(), emptySet())
    }

    private fun validatorWith(handlers: Set<String>): BpmnLocalRepairCapabilityValidator {
        val handlerInstances = handlers.map { stubHandler(it) }
        return BpmnLocalRepairCapabilityValidator(
            lintingPort = NoopLintingPort,
            modelFixHandlerRegistry = BpmnLocalModelFixHandlerRegistry(handlerInstances),
        )
    }

    private fun stubHandler(name: String): BpmnLocalModelFixHandler =
        object : BpmnLocalModelFixHandler {
            override val handlerName: String = name

            override fun buildPatch(
                definition: BpmnDefinition,
                elementId: String,
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
        override fun lint(bpmnXml: String): List<LintIssue> = emptyList()

        override fun autoFix(
            bpmnXml: String,
            issues: List<LintIssue>,
        ): BpmnAutoFixResult? = null

        override fun ruleDocs(ruleNames: Collection<String>): Map<String, String> = emptyMap()

        override fun lintRuleCapabilities(): Map<String, BpmnLintRuleCapability> = emptyMap()
    }
}
