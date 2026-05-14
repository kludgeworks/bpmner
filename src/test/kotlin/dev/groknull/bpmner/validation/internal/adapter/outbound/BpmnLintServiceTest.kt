package dev.groknull.bpmner.validation.internal.adapter.outbound

import dev.groknull.bpmner.core.BpmnLintPhase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BpmnLintServiceTest {
    private val service =
        BpmnLintService(
            catalogService = RuleCatalogService(),
            engine = BpmnLintJsEngine(),
            pklAdapter = PklRuleCapabilityAdapter(RuleCatalogService()),
        )

    @Test
    fun `lintConfig returns base properties for final phase`() {
        val config = service.lintConfig(BpmnLintPhase.FINAL_POST_LAYOUT)

        assertTrue(config.extends.contains("bpmnlint:recommended"))
    }

    @Test
    fun `lintConfig disables layout sensitive rules for rough phase`() {
        val config = service.lintConfig(BpmnLintPhase.SEMANTIC_PRE_LAYOUT)

        assertEquals("off", config.rules["no-overlapping-elements"])
        assertEquals("off", config.rules["bpmnlint/no-overlapping-elements"])
    }

    @Test
    fun `parseIssues correctly parses GraalJS JSON output`() {
        val json =
            """
            [
              {"id": "Task_1", "rule": "rule-1", "message": "error 1", "category": "error"},
              {"id": "Flow_1", "rule": "rule-2", "message": "error 2", "category": "warn"}
            ]
            """.trimIndent()

        val issues = service.parseIssues(json)

        assertEquals(2, issues.size)
        assertEquals("rule-1", issues[0].rule)
        assertEquals("rule-2", issues[1].rule)
    }

    @Test
    fun `parseAutoFixResult correctly parses GraalJS JSON output`() {
        val json =
            """
            {
              "changed": true,
              "xml": "FIXED_XML",
              "applied": [
                {"rule": "rule-1", "elementId": "Task_1", "message": "fixed it"}
              ],
              "skips": [],
              "errors": []
            }
            """.trimIndent()

        val result = service.parseAutoFixResult(json)

        assertTrue(result.changed)
        assertEquals("FIXED_XML", result.xml)
        assertEquals(1, result.applied.size)
        assertEquals("rule-1", result.applied[0].rule)
    }

    @Test
    fun `lintConfig merges Pkl defaults with overrides`() {
        val service =
            BpmnLintService(
                catalogService = RuleCatalogService(),
                engine = BpmnLintJsEngine(),
                pklAdapter = PklRuleCapabilityAdapter(RuleCatalogService()),
                properties =
                    BpmnLintProperties(
                        rules = mapOf("klm/act-verb-object-name" to "error"),
                    ),
            )

        val config = service.lintConfig()

        assertEquals("error", config.rules["klm/act-verb-object-name"])
    }
}
