/*
 * Copyright (c) 2026 The Project Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package dev.groknull.bpmner.validation.internal.adapter.outbound

import dev.groknull.bpmner.validation.BpmnLintPhase
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
                        rules = mapOf("bpmner/act-verb-object-name" to "error"),
                    ),
            )

        val config = service.lintConfig()

        assertEquals("error", config.rules["bpmner/act-verb-object-name"])
    }
}
