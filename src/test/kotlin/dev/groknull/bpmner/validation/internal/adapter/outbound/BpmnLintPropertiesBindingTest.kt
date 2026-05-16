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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

@SpringBootTest(classes = [BpmnLintPropertiesBindingTest.Config::class])
@TestPropertySource(
    properties = [
        "bpmner.lint.extends[0]=bpmnlint:recommended",
        "bpmner.lint.extends[1]=plugin:bpmner/recommended",
        "bpmner.lint.rules.[bpmner/act-verb-object-name]=error",
        "bpmner.lint.rules.[bpmner/act-loop-task-annotation]=error",
        "bpmner.lint.rules.[bpmner/act-mi-task-annotation]=error",
    ],
)
class BpmnLintPropertiesBindingTest {
    @EnableConfigurationProperties(BpmnLintProperties::class)
    class Config

    @Autowired
    lateinit var properties: BpmnLintProperties

    @Test
    fun `properties are bound correctly from spring environment`() {
        assertEquals(2, properties.extends.size)
        assertTrue(properties.extends.contains("bpmnlint:recommended"))
        assertTrue(properties.extends.contains("plugin:bpmner/recommended"))

        assertEquals("error", properties.rules["bpmner/act-verb-object-name"])
        assertEquals("error", properties.rules["bpmner/act-loop-task-annotation"])
        assertEquals("error", properties.rules["bpmner/act-mi-task-annotation"])
    }

    @Test
    fun `bpmner overrides are preserved exactly as configured`() {
        val expectedOverrides =
            listOf(
                "bpmner/act-verb-object-name",
                "bpmner/act-loop-task-annotation",
                "bpmner/act-mi-task-annotation",
            )

        assertTrue(
            properties.rules.keys.containsAll(expectedOverrides),
            "Expected overrides $expectedOverrides not found in ${properties.rules.keys}",
        )

        assertFalse(
            properties.rules.keys.any { it in expectedOverrides.map { rule -> rule.replace("/", "") } },
            "Configured plugin overrides must preserve their slash: ${properties.rules.keys}",
        )
    }
}
