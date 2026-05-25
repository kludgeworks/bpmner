/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.validation

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
