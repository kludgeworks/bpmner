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
}
