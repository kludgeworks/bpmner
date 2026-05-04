package dev.groknull.bpmner

import dev.groknull.bpmner.agent.BpmnLintService
import dev.groknull.bpmner.agent.BpmnXsdValidator
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BpmnValidationIntegrationTest {

    private val xsdValidator = BpmnXsdValidator()
    private val lintService = BpmnLintService()

    @BeforeAll
    fun setup() {
        lintService.init()
    }

    @AfterAll
    fun teardown() {
        lintService.destroy()
    }

    private fun loadBpmn(name: String): String =
        javaClass.getResourceAsStream("/bpmn/$name")?.bufferedReader()?.readText()
            ?: error("Test fixture not found: /bpmn/$name")

    @Test
    fun `valid process passes both XSD and bpmn-lint`() {
        val xml = loadBpmn("valid-process.bpmn")

        val xsdError = xsdValidator.validate(xml)
        assertNull(xsdError, "XSD validation should pass: $xsdError")

        val lintIssues = lintService.lint(xml)
        assertNotNull(lintIssues, "bpmn-lint should be available")
        assertTrue(lintIssues!!.isEmpty(), "bpmn-lint should report no issues: $lintIssues")
    }

    @Test
    fun `invalid XSD fails XSD validation`() {
        val xml = loadBpmn("invalid-xsd.bpmn")

        val xsdError = xsdValidator.validate(xml)
        assertNotNull(xsdError, "XSD validation should fail for invalid BPMN")
    }

    @Test
    fun `process without start event passes XSD but fails bpmn-lint`() {
        val xml = loadBpmn("no-start-event.bpmn")

        val xsdError = xsdValidator.validate(xml)
        assertNull(xsdError, "XSD validation should pass: $xsdError")

        val lintIssues = lintService.lint(xml)
        assertNotNull(lintIssues, "bpmn-lint should be available")
        assertTrue(lintIssues!!.isNotEmpty(), "bpmn-lint should report issues for a process without a start event")
        assertTrue(
            lintIssues.any { it.rule.contains("start-event", ignoreCase = true) },
            "Expected a start-event lint rule violation, got: $lintIssues"
        )
    }
}
