package dev.groknull.bpmner.agent

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
            "klm/act-01-verb-object-name",
            "klm/act-02-activity-label-capitalization",
            "klm/act-03-discouraged-business-verbs",
            "klm/act-12-loop-task-annotation",
            "klm/act-13-mi-task-annotation",
        )

        assertEquals(listOf("plugin:klm/recommended-error"), properties.extends)
        assertEquals("error", properties.rules["klm/act-01-verb-object-name"])
        assertTrue(properties.rules.keys.containsAll(expectedOverrides))
        assertFalse(
            properties.rules.keys.any { it.startsWith("klmact-") },
            "Spring binding must not strip '/' from KLM rule ids: ${properties.rules.keys}",
        )
        assertFalse(
            properties.rules.keys.any { it in expectedOverrides.map { rule -> rule.replace("/", "") } },
            "Configured KLM overrides must preserve their slash: ${properties.rules.keys}",
        )
    }
}
