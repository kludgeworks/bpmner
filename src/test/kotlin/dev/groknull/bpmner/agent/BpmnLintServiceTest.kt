package dev.groknull.bpmner.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BpmnLintServiceTest {

    private val service = BpmnLintService()

    @Test
    fun `parseIssues preserves upstream lint id and tolerates unknown fields`() {
        val issues = service.parseIssues(
            """
            [
              {
                "id": "Gateway_1",
                "rule": "conditional-flows",
                "message": "Gateway should name outgoing conditions",
                "category": "error",
                "column": 17,
                "custom": "kept"
              }
            ]
            """.trimIndent()
        )

        assertEquals(1, issues.size)
        assertEquals("Gateway_1", issues.single().id)
        assertEquals("conditional-flows", issues.single().rule)
        assertEquals("kept", issues.single().rawFields["custom"])
        assertTrue("column" in issues.single().rawFields)
    }

    @Test
    fun `semantic pre-layout lint disables layout-sensitive rules`() {
        val config = service.lintConfig(BpmnLintPhase.SEMANTIC_PRE_LAYOUT)

        assertEquals("off", config.rules["no-overlapping-elements"])
        assertEquals("off", config.rules["bpmnlint/no-overlapping-elements"])
    }

    @Test
    fun `final post-layout lint preserves configured rules`() {
        val configured = BpmnLintService(
            BpmnLintProperties(
                rules = mapOf("no-overlapping-elements" to "error"),
            )
        )

        val config = configured.lintConfig(BpmnLintPhase.FINAL_POST_LAYOUT)

        assertEquals("error", config.rules["no-overlapping-elements"])
    }
}
