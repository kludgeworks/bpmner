/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.inbound

import dev.groknull.bpmner.TestBpmnFixtures.testBpmnDefinition
import dev.groknull.bpmner.api.RepairKind
import dev.groknull.bpmner.api.RepairSafety
import dev.groknull.bpmner.layout.LayoutedBpmnXml
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnLayoutService
import dev.groknull.bpmner.validation.BpmnAutoFixChange
import dev.groknull.bpmner.validation.BpmnAutoFixResult
import dev.groknull.bpmner.validation.BpmnAutoFixSkip
import dev.groknull.bpmner.validation.BpmnLintRuleCapability
import dev.groknull.bpmner.validation.BpmnLintingPort
import dev.groknull.bpmner.validation.BpmnXsdValidationPort
import dev.groknull.bpmner.validation.LintIssue
import dev.groknull.bpmner.validation.ValidatedBpmnXml
import dev.groknull.bpmner.validation.XsdValidationIssue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("TooManyFunctions")
class BpmnLayoutAgentTest {
    private fun buildLayoutAgent(
        lintService: BpmnLintingPort,
        xsdValidator: BpmnXsdValidationPort,
        layoutService: BpmnLayoutService = RecordingLayoutService(),
    ): BpmnLayoutAgent = BpmnLayoutAgent(layoutService, lintService, xsdValidator)

    private fun localXmlCapability(
        id: String,
        handler: String = "clearName",
    ) = BpmnLintRuleCapability(
        id = id,
        kind = RepairKind.LOCAL_XML_FIX,
        repairSafety = RepairSafety.SAFE_AUTOMATIC,
        fixHandler = handler,
        handlerExists = true,
        replacementMap = null,
    )

    private fun llmCapability(id: String) =
        BpmnLintRuleCapability(
            id = id,
            kind = RepairKind.LLM_MODEL_PATCH,
            repairSafety = RepairSafety.LLM_ONLY,
            fixHandler = null,
            handlerExists = false,
            replacementMap = null,
        )

    // ---------------------------------------------------------------
    // validateFinalBpmnXml: XSD validation only
    // ---------------------------------------------------------------

    @Test
    fun `final validation passes when XSD is clean`() {
        val xsdValidator = RecordingXsdValidator(listOf(emptyList()))
        val agent = buildLayoutAgent(RecordingLintService(emptyList()), xsdValidator)

        val definition = testBpmnDefinition()
        val result = agent.validateFinalBpmnXml(LayoutedBpmnXml(definition, "<definitions />"))

        assertEquals("<definitions />", result.xml)
        assertTrue(result.diagnostics.isEmpty())
        assertEquals(1, xsdValidator.xmls.size)
    }

    @Test
    fun `final validation throws BpmnLayoutCorruptionException on XSD failure`() {
        val xsdValidator =
            RecordingXsdValidator(
                listOf(listOf(XsdValidationIssue("cvc-complex-type failure", "Task_1"))),
            )
        val agent = buildLayoutAgent(RecordingLintService(emptyList()), xsdValidator)

        val definition = testBpmnDefinition()
        val error =
            assertFailsWith<BpmnLayoutCorruptionException> {
                agent.validateFinalBpmnXml(LayoutedBpmnXml(definition, "<definitions />"))
            }
        assertTrue(error.message!!.contains("Auto-layout produced structurally invalid BPMN"))
        assertTrue(error.message!!.contains("cvc-complex-type failure"))
    }

    @Test
    fun `final validation does not invoke the linter`() {
        val xsdValidator = RecordingXsdValidator(listOf(emptyList()))
        val lintService = RecordingLintService(emptyList())
        val agent = buildLayoutAgent(lintService, xsdValidator)

        val definition = testBpmnDefinition()
        agent.validateFinalBpmnXml(LayoutedBpmnXml(definition, "<definitions />"))

        assertTrue(lintService.xmls.isEmpty(), "final validation must not call lint anymore")
    }

    // ---------------------------------------------------------------
    // autoFixBpmnXml: lint + filter + autoFix
    // ---------------------------------------------------------------

    @Test
    fun `auto-fix runs lint then autoFix and feeds layout the fixed xml`() {
        val xsdValidator = RecordingXsdValidator(listOf(emptyList(), emptyList()))
        val lintIssue =
            LintIssue(
                id = "Gateway_1",
                rule = "bpmner/gtw-converging-gateway-unnamed",
                message = "Converging gateway should remain unnamed",
            )
        val lintService =
            RecordingLintService(
                responses = listOf(listOf(lintIssue)),
                autoFixResponses =
                    listOf(
                        BpmnAutoFixResult(
                            changed = true,
                            xml = "<definitions fixed=\"true\" />",
                            applied =
                                listOf(
                                    BpmnAutoFixChange(
                                        rule = "bpmner/gtw-converging-gateway-unnamed",
                                        elementId = "Gateway_1",
                                        message = "Cleared gateway name",
                                    ),
                                ),
                        ),
                    ),
                capabilities =
                    mapOf("gtw-converging-gateway-unnamed" to localXmlCapability("gtw-converging-gateway-unnamed")),
            )
        val layoutService = RecordingLayoutService(listOf("<definitions fixed=\"true\" layouted=\"true\" />"))
        val agent = buildLayoutAgent(lintService, xsdValidator, layoutService)

        val definition = testBpmnDefinition()
        val autoFixed = agent.autoFixBpmnXml(ValidatedBpmnXml(definition, "<definitions />"))
        val layouted = agent.layoutBpmnXml(autoFixed)
        val result = agent.validateFinalBpmnXml(layouted)

        assertEquals("<definitions fixed=\"true\" />", autoFixed.xml)
        assertEquals("<definitions fixed=\"true\" />", layoutService.xmls.single())
        assertEquals("<definitions fixed=\"true\" layouted=\"true\" />", result.xml)
        assertEquals(listOf("<definitions />"), lintService.xmls)
        assertEquals(listOf("<definitions />"), lintService.autoFixXmls)
        assertEquals(
            listOf("bpmner/gtw-converging-gateway-unnamed"),
            lintService.autoFixIssues.single().map { it.rule },
        )
    }

    @Test
    fun `auto-fix returns no-op when autoFixer reports skipped for eligible rule`() {
        val lintIssue =
            LintIssue(
                id = "Gateway_1",
                rule = "bpmner/gtw-converging-gateway-unnamed",
                message = "Converging gateway should remain unnamed",
            )
        val lintService =
            RecordingLintService(
                responses = listOf(listOf(lintIssue)),
                autoFixResponses =
                    listOf(
                        BpmnAutoFixResult(
                            changed = false,
                            xml = "<definitions changed=\"ignored\" />",
                            skipped =
                                listOf(
                                    BpmnAutoFixSkip(
                                        rule = "bpmner/gtw-converging-gateway-unnamed",
                                        elementId = "Gateway_1",
                                        message = "No-op",
                                    ),
                                ),
                        ),
                    ),
                capabilities =
                    mapOf("gtw-converging-gateway-unnamed" to localXmlCapability("gtw-converging-gateway-unnamed")),
            )
        val agent = buildLayoutAgent(lintService, RecordingXsdValidator(listOf(emptyList())))

        val definition = testBpmnDefinition()
        val result = agent.autoFixBpmnXml(ValidatedBpmnXml(definition, "<definitions />"))

        assertEquals("<definitions />", result.xml)
        assertEquals(false, result.autoFixResult?.changed)
    }

    @Test
    fun `auto-fix skips calling autoFixer when no diagnostics are LOCAL_XML_FIX`() {
        val lintIssue = LintIssue(id = "Task_1", rule = "bpmner/some-llm-rule", message = "Needs LLM repair")
        val lintService =
            RecordingLintService(
                responses = listOf(listOf(lintIssue)),
                capabilities = mapOf("some-llm-rule" to llmCapability("some-llm-rule")),
            )
        val agent = buildLayoutAgent(lintService, RecordingXsdValidator(listOf(emptyList())))

        val definition = testBpmnDefinition()
        val result = agent.autoFixBpmnXml(ValidatedBpmnXml(definition, "<definitions />"))

        assertEquals("<definitions />", result.xml)
        assertNull(result.autoFixResult, "filtered-out diagnostics must not invoke the auto-fixer")
        assertTrue(lintService.autoFixXmls.isEmpty(), "auto-fixer must not be called when nothing is eligible")
    }

    @Test
    fun `auto-fix filters out non-LOCAL_XML_FIX diagnostics before calling autoFixer`() {
        val localIssue =
            LintIssue(
                id = "Gateway_1",
                rule = "bpmner/gtw-converging-gateway-unnamed",
                message = "Converging gateway should remain unnamed",
            )
        val llmIssue =
            LintIssue(id = "Task_1", rule = "bpmner/some-llm-rule", message = "Needs LLM repair")
        val lintService =
            RecordingLintService(
                responses = listOf(listOf(localIssue, llmIssue)),
                autoFixResponses =
                    listOf(
                        BpmnAutoFixResult(
                            changed = true,
                            xml = "<definitions fixed=\"true\" />",
                            applied =
                                listOf(
                                    BpmnAutoFixChange(
                                        rule = "bpmner/gtw-converging-gateway-unnamed",
                                        elementId = "Gateway_1",
                                        message = "Cleared gateway name",
                                    ),
                                ),
                        ),
                    ),
                capabilities =
                    mapOf(
                        "gtw-converging-gateway-unnamed" to localXmlCapability("gtw-converging-gateway-unnamed"),
                        "some-llm-rule" to llmCapability("some-llm-rule"),
                    ),
            )
        val agent = buildLayoutAgent(lintService, RecordingXsdValidator(listOf(emptyList())))

        val definition = testBpmnDefinition()
        agent.autoFixBpmnXml(ValidatedBpmnXml(definition, "<definitions />"))

        assertEquals(
            listOf("bpmner/gtw-converging-gateway-unnamed"),
            lintService.autoFixIssues.single().map { it.rule },
            "auto-fixer must only see the LOCAL_XML_FIX diagnostic",
        )
    }

    @Test
    fun `auto-fix filters out diagnostics whose rule is missing from capability map`() {
        val unknownIssue =
            LintIssue(id = "Task_1", rule = "bpmner/unknown-rule", message = "Rule has no capability entry")
        val lintService =
            RecordingLintService(
                responses = listOf(listOf(unknownIssue)),
                capabilities = emptyMap(),
            )
        val agent = buildLayoutAgent(lintService, RecordingXsdValidator(listOf(emptyList())))

        val definition = testBpmnDefinition()
        val result = agent.autoFixBpmnXml(ValidatedBpmnXml(definition, "<definitions />"))

        assertNull(result.autoFixResult, "diagnostics with no capability entry must not reach the auto-fixer")
        assertTrue(lintService.autoFixXmls.isEmpty())
    }

    @Test
    fun `auto-fix unavailable keeps original validated xml for layout`() {
        val lintIssue =
            LintIssue(
                id = "Gateway_1",
                rule = "bpmner/gtw-converging-gateway-unnamed",
                message = "Converging gateway should remain unnamed",
            )
        val lintService =
            RecordingLintService(
                responses = listOf(listOf(lintIssue)),
                autoFixResponses = listOf(null),
                capabilities =
                    mapOf("gtw-converging-gateway-unnamed" to localXmlCapability("gtw-converging-gateway-unnamed")),
            )
        val layoutService = RecordingLayoutService()
        val agent = buildLayoutAgent(lintService, RecordingXsdValidator(listOf(emptyList())), layoutService)

        val definition = testBpmnDefinition()
        val autoFixed = agent.autoFixBpmnXml(ValidatedBpmnXml(definition, "<definitions />"))
        agent.layoutBpmnXml(autoFixed)

        assertEquals("<definitions />", autoFixed.xml)
        assertEquals(listOf("<definitions />"), layoutService.xmls)
    }

    @Test
    fun `auto-fix that invalidates XSD keeps original validated xml for layout`() {
        val lintIssue =
            LintIssue(
                id = "Gateway_1",
                rule = "bpmner/gtw-converging-gateway-unnamed",
                message = "Converging gateway should remain unnamed",
            )
        val lintService =
            RecordingLintService(
                responses = listOf(listOf(lintIssue)),
                autoFixResponses =
                    listOf(
                        BpmnAutoFixResult(
                            changed = true,
                            xml = "<definitions><broken /></definitions>",
                            applied =
                                listOf(
                                    BpmnAutoFixChange(
                                        rule = "bpmner/gtw-converging-gateway-unnamed",
                                        elementId = "Gateway_1",
                                        message = "Cleared gateway name",
                                    ),
                                ),
                        ),
                    ),
                capabilities =
                    mapOf("gtw-converging-gateway-unnamed" to localXmlCapability("gtw-converging-gateway-unnamed")),
            )
        val xsdValidator =
            RecordingXsdValidator(
                listOf(listOf(XsdValidationIssue("cvc-complex-type failure near Gateway_1", "Gateway_1"))),
            )
        val layoutService = RecordingLayoutService()
        val agent = buildLayoutAgent(lintService, xsdValidator, layoutService)

        val definition = testBpmnDefinition()
        val autoFixed = agent.autoFixBpmnXml(ValidatedBpmnXml(definition, "<definitions />"))
        agent.layoutBpmnXml(autoFixed)

        assertEquals("<definitions />", autoFixed.xml)
        assertEquals(true, autoFixed.autoFixResult?.changed)
        assertEquals(listOf("<definitions><broken /></definitions>"), xsdValidator.xmls)
        assertEquals(listOf("<definitions />"), layoutService.xmls)
    }

    @Test
    fun `pipeline ordering is lint then filter then autoFix then xsd then layout then xsd`() {
        val lintIssue =
            LintIssue(
                id = "Gateway_1",
                rule = "bpmner/gtw-converging-gateway-unnamed",
                message = "Converging gateway should remain unnamed",
            )
        val callLog = mutableListOf<String>()
        val lintService =
            RecordingLintService(
                responses = listOf(listOf(lintIssue)),
                autoFixResponses =
                    listOf(
                        BpmnAutoFixResult(
                            changed = true,
                            xml = "<definitions fixed=\"true\" />",
                            applied =
                                listOf(
                                    BpmnAutoFixChange(
                                        rule = "bpmner/gtw-converging-gateway-unnamed",
                                        elementId = "Gateway_1",
                                        message = "Cleared gateway name",
                                    ),
                                ),
                        ),
                    ),
                capabilities =
                    mapOf("gtw-converging-gateway-unnamed" to localXmlCapability("gtw-converging-gateway-unnamed")),
                callLog = callLog,
            )
        val xsdValidator = RecordingXsdValidator(listOf(emptyList(), emptyList()), callLog = callLog)
        val layoutService = RecordingLayoutService(listOf("<definitions laid-out=\"true\" />"), callLog = callLog)
        val agent = buildLayoutAgent(lintService, xsdValidator, layoutService)

        val definition = testBpmnDefinition()
        val autoFixed = agent.autoFixBpmnXml(ValidatedBpmnXml(definition, "<definitions />"))
        val layouted = agent.layoutBpmnXml(autoFixed)
        agent.validateFinalBpmnXml(layouted)

        assertEquals(
            listOf("lint", "autoFix", "xsd", "layout", "xsd"),
            callLog,
        )
    }

    @Suppress("TooManyFunctions")
    private class RecordingLayoutService(
        private val responses: List<String> = emptyList(),
        private val callLog: MutableList<String>? = null,
    ) : BpmnLayoutService() {
        val xmls = mutableListOf<String>()
        private var index = 0

        override fun layout(xml: String): String {
            xmls += xml
            callLog?.add("layout")
            return if (index < responses.size) responses[index++] else xml
        }
    }

    @Suppress("TooManyFunctions")
    private class RecordingLintService(
        private val responses: List<List<LintIssue>?>,
        private val docs: Map<String, String> = emptyMap(),
        private val autoFixResponses: List<BpmnAutoFixResult?> = emptyList(),
        private val capabilities: Map<String, BpmnLintRuleCapability> = emptyMap(),
        private val callLog: MutableList<String>? = null,
    ) : BpmnLintingPort {
        val xmls = mutableListOf<String>()
        val autoFixXmls = mutableListOf<String>()
        val autoFixIssues = mutableListOf<List<LintIssue>>()
        private var index = 0
        private var autoFixIndex = 0

        override fun lint(bpmnXml: String): List<LintIssue>? {
            xmls += bpmnXml
            callLog?.add("lint")
            return responses[index++]
        }

        override fun autoFix(
            bpmnXml: String,
            issues: List<LintIssue>,
        ): BpmnAutoFixResult? {
            autoFixXmls += bpmnXml
            autoFixIssues += issues
            callLog?.add("autoFix")
            return if (autoFixIndex < autoFixResponses.size) autoFixResponses[autoFixIndex++] else null
        }

        override fun ruleDocs(ruleNames: Collection<String>): Map<String, String> =
            buildMap {
                ruleNames.distinct().forEach { ruleName ->
                    docs[ruleName]?.let { put(ruleName, it) }
                }
            }

        override fun lintRuleCapabilities(): Map<String, BpmnLintRuleCapability> = capabilities
    }

    @Suppress("TooManyFunctions")
    private class RecordingXsdValidator(
        private val responses: List<List<XsdValidationIssue>>,
        private val callLog: MutableList<String>? = null,
    ) : BpmnXsdValidationPort {
        val xmls = mutableListOf<String>()
        private var index = 0

        override fun validateDetailed(bpmnXml: String): List<XsdValidationIssue> {
            xmls += bpmnXml
            callLog?.add("xsd")
            return responses[index++]
        }
    }
}
