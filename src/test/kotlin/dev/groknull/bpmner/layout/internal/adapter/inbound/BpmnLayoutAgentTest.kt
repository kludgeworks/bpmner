package dev.groknull.bpmner.layout.internal.adapter.inbound

import dev.groknull.bpmner.core.BpmnAutoFixChange
import dev.groknull.bpmner.core.BpmnAutoFixResult
import dev.groknull.bpmner.core.BpmnAutoFixSkip
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnLintPhase
import dev.groknull.bpmner.core.BpmnLintRuleCapability
import dev.groknull.bpmner.core.BpmnRepairSafety
import dev.groknull.bpmner.core.LayoutedBpmnXml
import dev.groknull.bpmner.core.LintIssue
import dev.groknull.bpmner.core.RepairKind
import dev.groknull.bpmner.core.ValidatedBpmnXml
import dev.groknull.bpmner.core.XsdValidationIssue
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnLayoutService
import dev.groknull.bpmner.validation.BpmnLintingPort
import dev.groknull.bpmner.validation.BpmnXsdValidationPort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        repairSafety = BpmnRepairSafety.SAFE_AUTOMATIC,
        fixHandler = handler,
        handlerExists = true,
        replacementMap = null,
    )

    private fun llmCapability(id: String) =
        BpmnLintRuleCapability(
            id = id,
            kind = RepairKind.LLM_MODEL_PATCH,
            repairSafety = BpmnRepairSafety.LLM_ONLY,
            fixHandler = null,
            handlerExists = false,
            replacementMap = null,
        )

    private fun layoutSensitiveCapability(id: String) =
        BpmnLintRuleCapability(
            id = id,
            kind = RepairKind.UNFIXABLE,
            repairSafety = BpmnRepairSafety.LLM_ONLY,
            fixHandler = null,
            handlerExists = false,
            replacementMap = null,
            layoutSensitive = true,
        )

    private fun dummyDefinition() = BpmnDefinition("Process_1", "Dummy", emptyList(), emptyList())

    @Test
    fun `final validation runs full post-layout lint and succeeds`() {
        val xsdValidator = RecordingXsdValidator(listOf(emptyList()))
        val lintService = RecordingLintService(listOf(emptyList()))
        val agent = buildLayoutAgent(lintService, xsdValidator)

        val result = agent.validateFinalBpmnXml(LayoutedBpmnXml(dummyDefinition(), "<definitions />"))

        assertEquals("<definitions />", result.xml)
        assertTrue(result.diagnostics.isEmpty())
        assertEquals(listOf(BpmnLintPhase.FINAL_POST_LAYOUT), lintService.phases)
        assertEquals(1, xsdValidator.xmls.size)
    }

    @Test
    fun `auto-fix runs before layout and final validation`() {
        val xsdValidator = RecordingXsdValidator(listOf(emptyList(), emptyList()))
        val lintIssue =
            LintIssue(
                id = "Gateway_1",
                rule = "bpmner/gtw-converging-gateway-unnamed",
                message = "Converging gateway should remain unnamed",
            )
        val lintService =
            RecordingLintService(
                responses = listOf(listOf(lintIssue), emptyList()),
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
                capabilities = mapOf("gtw-converging-gateway-unnamed" to localXmlCapability("gtw-converging-gateway-unnamed")),
            )
        val layoutService = RecordingLayoutService(listOf("<definitions fixed=\"true\" layouted=\"true\" />"))
        val agent = buildLayoutAgent(lintService, xsdValidator, layoutService)

        val autoFixed = agent.autoFixBpmnXml(ValidatedBpmnXml(dummyDefinition(), "<definitions />"))
        val layouted = agent.layoutBpmnXml(autoFixed)
        val result = agent.validateFinalBpmnXml(layouted)

        assertEquals("<definitions fixed=\"true\" />", autoFixed.xml)
        assertEquals("<definitions fixed=\"true\" />", layoutService.xmls.single())
        assertEquals("<definitions fixed=\"true\" layouted=\"true\" />", result.xml)
        assertEquals(
            listOf("<definitions />", "<definitions fixed=\"true\" layouted=\"true\" />"),
            lintService.xmls,
        )
        assertEquals(listOf("<definitions />"), lintService.autoFixXmls)
        assertEquals(
            listOf("bpmner/gtw-converging-gateway-unnamed"),
            lintService.autoFixIssues.single().map { it.rule },
            "auto-fix should be called with the eligible LOCAL_XML_FIX issue",
        )
        assertEquals(
            listOf("<definitions fixed=\"true\" />", "<definitions fixed=\"true\" layouted=\"true\" />"),
            xsdValidator.xmls,
        )
        assertEquals(listOf(BpmnLintPhase.SEMANTIC_PRE_LAYOUT), lintService.autoFixPhases)
        assertEquals(
            listOf(BpmnLintPhase.SEMANTIC_PRE_LAYOUT, BpmnLintPhase.FINAL_POST_LAYOUT),
            lintService.phases,
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
                capabilities = mapOf("gtw-converging-gateway-unnamed" to localXmlCapability("gtw-converging-gateway-unnamed")),
            )
        val agent = buildLayoutAgent(lintService, RecordingXsdValidator(listOf(emptyList())))

        val result = agent.autoFixBpmnXml(ValidatedBpmnXml(dummyDefinition(), "<definitions />"))

        assertEquals("<definitions />", result.xml)
        assertEquals(false, result.autoFixResult?.changed)
        assertEquals(listOf(BpmnLintPhase.SEMANTIC_PRE_LAYOUT), lintService.phases)
        assertEquals(listOf(BpmnLintPhase.SEMANTIC_PRE_LAYOUT), lintService.autoFixPhases)
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

        val result = agent.autoFixBpmnXml(ValidatedBpmnXml(dummyDefinition(), "<definitions />"))

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

        agent.autoFixBpmnXml(ValidatedBpmnXml(dummyDefinition(), "<definitions />"))

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

        val result = agent.autoFixBpmnXml(ValidatedBpmnXml(dummyDefinition(), "<definitions />"))

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
                capabilities = mapOf("gtw-converging-gateway-unnamed" to localXmlCapability("gtw-converging-gateway-unnamed")),
            )
        val layoutService = RecordingLayoutService()
        val agent = buildLayoutAgent(lintService, RecordingXsdValidator(listOf(emptyList())), layoutService)

        val autoFixed = agent.autoFixBpmnXml(ValidatedBpmnXml(dummyDefinition(), "<definitions />"))
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
                capabilities = mapOf("gtw-converging-gateway-unnamed" to localXmlCapability("gtw-converging-gateway-unnamed")),
            )
        val xsdValidator =
            RecordingXsdValidator(
                listOf(listOf(XsdValidationIssue("cvc-complex-type failure near Gateway_1", "Gateway_1"))),
            )
        val layoutService = RecordingLayoutService()
        val agent = buildLayoutAgent(lintService, xsdValidator, layoutService)

        val autoFixed = agent.autoFixBpmnXml(ValidatedBpmnXml(dummyDefinition(), "<definitions />"))
        agent.layoutBpmnXml(autoFixed)

        assertEquals("<definitions />", autoFixed.xml)
        assertEquals(true, autoFixed.autoFixResult?.changed)
        assertEquals(listOf("<definitions><broken /></definitions>"), xsdValidator.xmls)
        assertEquals(listOf("<definitions />"), layoutService.xmls)
    }

    @Test
    fun `auto-fix stage ordering is lint then filter then autoFix then xsd then layout then final lint`() {
        val lintIssue =
            LintIssue(
                id = "Gateway_1",
                rule = "bpmner/gtw-converging-gateway-unnamed",
                message = "Converging gateway should remain unnamed",
            )
        val callLog = mutableListOf<String>()
        val lintService =
            RecordingLintService(
                responses = listOf(listOf(lintIssue), emptyList()),
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
                capabilities = mapOf("gtw-converging-gateway-unnamed" to localXmlCapability("gtw-converging-gateway-unnamed")),
                callLog = callLog,
            )
        val xsdValidator = RecordingXsdValidator(listOf(emptyList(), emptyList()), callLog = callLog)
        val layoutService = RecordingLayoutService(listOf("<definitions laid-out=\"true\" />"), callLog = callLog)
        val agent = buildLayoutAgent(lintService, xsdValidator, layoutService)

        val autoFixed = agent.autoFixBpmnXml(ValidatedBpmnXml(dummyDefinition(), "<definitions />"))
        val layouted = agent.layoutBpmnXml(autoFixed)
        agent.validateFinalBpmnXml(layouted)

        assertEquals(
            listOf(
                "lint:SEMANTIC_PRE_LAYOUT",
                "autoFix:SEMANTIC_PRE_LAYOUT",
                "xsd",
                "layout",
                "xsd",
                "lint:FINAL_POST_LAYOUT",
            ),
            callLog,
        )
    }

    @Test
    fun `final validation fails clearly when layout diagnostics remain`() {
        val xsdValidator = RecordingXsdValidator(listOf(emptyList()))
        val lintService =
            RecordingLintService(
                listOf(
                    listOf(
                        LintIssue(
                            id = "Task_1",
                            rule = "no-overlapping-elements",
                            message = "Element overlaps with Task_2",
                        ),
                    ),
                ),
                capabilities = mapOf("no-overlapping-elements" to layoutSensitiveCapability("no-overlapping-elements")),
            )
        val agent = buildLayoutAgent(lintService, xsdValidator)

        val error =
            assertFailsWith<BpmnFinalValidationException> {
                agent.validateFinalBpmnXml(LayoutedBpmnXml(dummyDefinition(), "<definitions />"))
            }

        assertTrue(error.message!!.contains("Final BPMN validation failed after auto-layout"))
        assertTrue(error.message!!.contains("layout diagnostics remain after auto-layout"))
        assertTrue(error.message!!.contains("no-overlapping-elements"))
        assertEquals(listOf(BpmnLintPhase.FINAL_POST_LAYOUT), lintService.phases)
    }

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

    private class RecordingLintService(
        private val responses: List<List<LintIssue>?>,
        private val docs: Map<String, String> = emptyMap(),
        private val autoFixResponses: List<BpmnAutoFixResult?> = emptyList(),
        private val capabilities: Map<String, BpmnLintRuleCapability> = emptyMap(),
        private val callLog: MutableList<String>? = null,
    ) : BpmnLintingPort {
        val xmls = mutableListOf<String>()
        val phases = mutableListOf<BpmnLintPhase>()
        val autoFixXmls = mutableListOf<String>()
        val autoFixIssues = mutableListOf<List<LintIssue>>()
        val autoFixPhases = mutableListOf<BpmnLintPhase>()
        private var index = 0
        private var autoFixIndex = 0

        override fun lint(
            bpmnXml: String,
            phase: BpmnLintPhase,
        ): List<LintIssue>? {
            xmls += bpmnXml
            phases += phase
            callLog?.add("lint:${phase.name}")
            return responses[index++]
        }

        override fun autoFix(
            bpmnXml: String,
            issues: List<LintIssue>,
            phase: BpmnLintPhase,
        ): BpmnAutoFixResult? {
            autoFixXmls += bpmnXml
            autoFixIssues += issues
            autoFixPhases += phase
            callLog?.add("autoFix:${phase.name}")
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
