package dev.groknull.bpmner.validation.internal.adapter.outbound

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource

class BpmnLintPropertiesBindingTest {

    @Test
    fun `application yaml preserves lint rule ids containing slash`() {
        val environment = StandardEnvironment()
        val propertySources = YamlPropertySourceLoader().load(
            "application",
            ClassPathResource("application.yaml"),
        )
        propertySources.reversed().forEach {
            environment.propertySources.addFirst(it)
        }

        val properties = Binder.get(environment)
            .bind("bpmner.lint", Bindable.of(BpmnLintProperties::class.java))
            .orElseThrow { IllegalStateException("bpmner.lint properties should bind from application.yaml") }

        val expectedOverrides = setOf(
            "bpmner/act-01-verb-object-name",
            "bpmner/act-02-activity-label-capitalization",
            "bpmner/act-03-discouraged-business-verbs",
            "bpmner/act-12-loop-task-annotation",
            "bpmner/act-13-mi-task-annotation",
        )

        assertEquals(listOf("plugin:bpmner/recommended-error"), properties.extends)
        assertEquals("error", properties.rules["bpmner/act-01-verb-object-name"])
        assertTrue(properties.rules.keys.containsAll(expectedOverrides))
        assertFalse(
            properties.rules.keys.any { it.startsWith("bpmneract-") },
            "Spring binding must not strip '/' from BPMNER rule ids: ${properties.rules.keys}",
        )
        assertFalse(
            properties.rules.keys.any { it in expectedOverrides.map { rule -> rule.replace("/", "") } },
            "Configured BPMNER overrides must preserve their slash: ${properties.rules.keys}",
        )
    }
}
