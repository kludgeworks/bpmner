package dev.groknull.bpmner.layout

import dev.groknull.bpmner.core.BpmnAutoFixChange
import dev.groknull.bpmner.core.BpmnAutoFixResult
import dev.groknull.bpmner.core.BpmnAutoFixSkip
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnLintPhase
import dev.groknull.bpmner.core.LintIssue
import dev.groknull.bpmner.core.LayoutedBpmnXml
import dev.groknull.bpmner.core.ValidatedBpmnXml
import dev.groknull.bpmner.core.XsdValidationIssue
import dev.groknull.bpmner.layout.internal.BpmnLayoutService
import dev.groknull.bpmner.validation.BpmnLintingPort
import dev.groknull.bpmner.validation.BpmnXsdValidationPort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BpmnLayoutAgentTest {

    private fun buildLayoutAgent(
        lintService: BpmnLintingPort,
        xsdValidator: BpmnXsdValidationPort,
        layoutService: BpmnLayoutService = RecordingLayoutService(),
    ): BpmnLayoutAgent = BpmnLayoutAgent(layoutService, lintService, xsdValidator)

    @Test
    fun `final validation runs full post-layout lint and succeeds`() {
        val xsdValidator = RecordingXsdValidator(listOf(emptyList()))
        val lintService = RecordingLintService(listOf(emptyList()))
        val agent = buildLayoutAgent(lintService, xsdValidator)

        val result = agent.validateFinalBpmnXml(LayoutedBpmnXml("<definitions />"))

        assertEquals("<definitions />", result.xml)
        assertTrue(result.diagnostics.isEmpty())
        assertEquals(listOf(BpmnLintPhase.FINAL_POST_LAYOUT), lintService.phases)
        assertEquals(1, xsdValidator.xmls.size)
    }

    @Test
    fun `auto-fix runs before layout and final validation`() {
        val xsdValidator = RecordingXsdValidator(listOf(emptyList(), emptyList()))
        val lintIssue = LintIssue(
            id = "Gateway_1",
            rule = "bpmner/gtw-02-converging-gateway-unnamed",
            message = "Converging gateway should remain unnamed",
        )
        val lintService = RecordingLintService(
            responses = listOf(listOf(lintIssue), emptyList()),
            autoFixResponses = listOf(
                BpmnAutoFixResult(
                    changed = true,
                    xml = "<definitions fixed=\"true\" />",
                    applied = listOf(
                        BpmnAutoFixChange(
                            rule = "bpmner/gtw-02-converging-gateway-unnamed",
                            elementId = "Gateway_1",
                            message = "Cleared gateway name",
                        )
                    ),
                )
            ),
        )
        val layoutService = RecordingLayoutService(listOf("<definitions fixed=\"true\" layouted=\"true\" />"))
        val agent = buildLayoutAgent(lintService, xsdValidator, layoutService)

        val autoFixed = agent.autoFixBpmnXml(ValidatedBpmnXml("<definitions />"))
        val layouted = agent.layoutBpmnXml(autoFixed)
        val result = agent.validateFinalBpmnXml(layouted)

        assertEquals("<definitions fixed=\"true\" />", autoFixed.xml)
        assertEquals("<definitions fixed=\"true\" />", layoutService.xmls.single())
        assertEquals("<definitions fixed=\"true\" layouted=\"true\" />", result.xml)
        assertEquals(listOf("<definitions />", "<definitions fixed=\"true\" layouted=\"true\" />"), lintService.xmls)
        assertEquals(listOf("<definitions />"), lintService.autoFixXmls)
        assertEquals(listOf("<definitions fixed=\"true\" />", "<definitions fixed=\"true\" layouted=\"true\" />"), xsdValidator.xmls)
        assertEquals(listOf(BpmnLintPhase.SEMANTIC_PRE_LAYOUT), lintService.autoFixPhases)
        assertEquals(listOf(BpmnLintPhase.SEMANTIC_PRE_LAYOUT, BpmnLintPhase.FINAL_POST_LAYOUT), lintService.phases)
    }

    @Test
    fun `auto-fix no-op keeps original validated xml`() {
        val lintIssue = LintIssue(id = "Task_1", rule = "start-event-required", message = "Missing start event")
        val lintService = RecordingLintService(
            responses = listOf(listOf(lintIssue)),
            autoFixResponses = listOf(
                BpmnAutoFixResult(
                    changed = false,
                    xml = "<definitions changed=\"ignored\" />",
                    skipped = listOf(BpmnAutoFixSkip(rule = "start-event-required", elementId = "Task_1", message = "Rule is not auto-fixable")),
                )
            ),
        )
        val agent = buildLayoutAgent(lintService, RecordingXsdValidator(listOf(emptyList())))

        val result = agent.autoFixBpmnXml(ValidatedBpmnXml("<definitions />"))

        assertEquals("<definitions />", result.xml)
        assertEquals(false, result.autoFixResult?.changed)
        assertEquals(listOf(BpmnLintPhase.SEMANTIC_PRE_LAYOUT), lintService.phases)
        assertEquals(listOf(BpmnLintPhase.SEMANTIC_PRE_LAYOUT), lintService.autoFixPhases)
    }

    @Test
    fun `auto-fix unavailable keeps original validated xml for layout`() {
        val lintIssue = LintIssue(id = "Task_1", rule = "start-event-required", message = "Missing start event")
        val lintService = RecordingLintService(
            responses = listOf(listOf(lintIssue)),
            autoFixResponses = listOf(null),
        )
        val layoutService = RecordingLayoutService()
        val agent = buildLayoutAgent(lintService, RecordingXsdValidator(listOf(emptyList())), layoutService)

        val autoFixed = agent.autoFixBpmnXml(ValidatedBpmnXml("<definitions />"))
        agent.layoutBpmnXml(autoFixed)

        assertEquals("<definitions />", autoFixed.xml)
        assertEquals(listOf("<definitions />"), layoutService.xmls)
    }

    @Test
    fun `auto-fix that invalidates XSD keeps original validated xml for layout`() {
        val lintIssue = LintIssue(
            id = "Gateway_1",
            rule = "bpmner/gtw-02-converging-gateway-unnamed",
            message = "Converging gateway should remain unnamed",
        )
        val lintService = RecordingLintService(
            responses = listOf(listOf(lintIssue)),
            autoFixResponses = listOf(
                BpmnAutoFixResult(
                    changed = true,
                    xml = "<definitions><broken /></definitions>",
                    applied = listOf(
                        BpmnAutoFixChange(
                            rule = "bpmner/gtw-02-converging-gateway-unnamed",
                            elementId = "Gateway_1",
                            message = "Cleared gateway name",
                        )
                    ),
                )
            ),
        )
        val xsdValidator = RecordingXsdValidator(
            listOf(listOf(XsdValidationIssue("cvc-complex-type failure near Gateway_1", "Gateway_1")))
        )
        val layoutService = RecordingLayoutService()
        val agent = buildLayoutAgent(lintService, xsdValidator, layoutService)

        val autoFixed = agent.autoFixBpmnXml(ValidatedBpmnXml("<definitions />"))
        agent.layoutBpmnXml(autoFixed)

        assertEquals("<definitions />", autoFixed.xml)
        assertEquals(true, autoFixed.autoFixResult?.changed)
        assertEquals(listOf("<definitions><broken /></definitions>"), xsdValidator.xmls)
        assertEquals(listOf("<definitions />"), layoutService.xmls)
    }

    @Test
    fun `final validation fails clearly when layout diagnostics remain`() {
        val xsdValidator = RecordingXsdValidator(listOf(emptyList()))
        val lintService = RecordingLintService(
            listOf(
                listOf(
                    LintIssue(
                        id = "Task_1",
                        rule = "no-overlapping-elements",
                        message = "Element overlaps with Task_2",
                    )
                )
            )
        )
        val agent = buildLayoutAgent(lintService, xsdValidator)

        val error = assertFailsWith<BpmnFinalValidationException> {
            agent.validateFinalBpmnXml(LayoutedBpmnXml("<definitions />"))
        }

        assertTrue(error.message!!.contains("Final BPMN validation failed after auto-layout"))
        assertTrue(error.message!!.contains("layout diagnostics remain after auto-layout"))
        assertTrue(error.message!!.contains("no-overlapping-elements"))
        assertEquals(listOf(BpmnLintPhase.FINAL_POST_LAYOUT), lintService.phases)
    }

    private class RecordingLayoutService(
        private val responses: List<String> = emptyList(),
    ) : BpmnLayoutService() {
        val xmls = mutableListOf<String>()
        private var index = 0

        override fun layout(xml: String): String {
            xmls += xml
            return if (index < responses.size) responses[index++] else xml
        }
    }

    private class RecordingLintService(
        private val responses: List<List<LintIssue>?>,
        private val docs: Map<String, String> = emptyMap(),
        private val autoFixResponses: List<BpmnAutoFixResult?> = emptyList(),
    ) : BpmnLintingPort {
        val xmls = mutableListOf<String>()
        val phases = mutableListOf<BpmnLintPhase>()
        val autoFixXmls = mutableListOf<String>()
        val autoFixIssues = mutableListOf<List<LintIssue>>()
        val autoFixPhases = mutableListOf<BpmnLintPhase>()
        private var index = 0
        private var autoFixIndex = 0

        override fun lint(bpmnXml: String, phase: BpmnLintPhase): List<LintIssue>? {
            xmls += bpmnXml
            phases += phase
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
            return if (autoFixIndex < autoFixResponses.size) autoFixResponses[autoFixIndex++] else null
        }

        override fun ruleDocs(ruleNames: Collection<String>): Map<String, String> =
            buildMap {
                ruleNames.distinct().forEach { ruleName ->
                    docs[ruleName]?.let { put(ruleName, it) }
                }
            }
    }

    private class RecordingXsdValidator(
        private val responses: List<List<XsdValidationIssue>>,
    ) : BpmnXsdValidationPort {
        val xmls = mutableListOf<String>()
        private var index = 0

        override fun validateDetailed(bpmnXml: String): List<XsdValidationIssue> {
            xmls += bpmnXml
            return responses[index++]
        }
    }
}
