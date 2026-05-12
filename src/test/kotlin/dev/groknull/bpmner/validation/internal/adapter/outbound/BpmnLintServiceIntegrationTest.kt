@file:Suppress(
    "MaxLineLength",
    "UnusedPrivateProperty",
)

package dev.groknull.bpmner.validation.internal.adapter.outbound

import dev.groknull.bpmner.core.BpmnLintPhase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BpmnLintServiceIntegrationTest {
    @Test
    fun `lint returns issues for invalid BPMN XML using GraalJS bundle`() {
        val service = BpmnLintService(catalogService = RuleCatalogService())
        service.init()

        val invalidXml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
              <bpmn:process id="Process_1" />
            </bpmn:definitions>
            """.trimIndent()

        val issues = service.lint(invalidXml, BpmnLintPhase.FINAL_POST_LAYOUT)

        assertNotNull(issues, "Issues should not be null when linting is functional")
        issues!!
        assertTrue(issues.isNotEmpty(), "Should find issues in minimal process")
        assertTrue(
            issues.any {
                it.rule == "start-event-required" || it.rule == "end-event-required"
            },
            "Should find expected core issues. Found: ${issues.map { it.rule }}",
        )
    }

    @Test
    fun `lint detects duplicate diagram elements using KLM rule`() {
        val service =
            BpmnLintService(
                catalogService = RuleCatalogService(),
                properties =
                    BpmnLintProperties(
                        extends = listOf("bpmnlint:recommended", "plugin:klm/recommended"),
                    ),
            )
        service.init()

        val duplicateDiagramXml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
              xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
              id="Definitions_1" targetNamespace="http://example.com/bpmn">
              <bpmn:process id="Process_1" />
              <bpmndi:BPMNDiagram id="Diagram_1"><bpmndi:BPMNPlane id="Plane_1" bpmnElement="Process_1" /></bpmndi:BPMNDiagram>
              <bpmndi:BPMNDiagram id="Diagram_2"><bpmndi:BPMNPlane id="Plane_2" bpmnElement="Process_1" /></bpmndi:BPMNDiagram>
            </bpmn:definitions>
            """.trimIndent()

        val issues = service.lint(duplicateDiagramXml, BpmnLintPhase.FINAL_POST_LAYOUT)

        assertNotNull(issues)
        issues!!
        assertTrue(issues.any { it.rule == "klm/gen-no-duplicate-diagrams" }, "Should find KLM duplicate diagram issue")
    }

    @Test
    fun `autoFix clears named converging gateway using GraalJS bundle`() {
        val service =
            BpmnLintService(
                catalogService = RuleCatalogService(),
                properties =
                    BpmnLintProperties(
                        extends = listOf("plugin:klm/recommended"),
                    ),
            )
        service.init()

        val xml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
              <bpmn:process id="Process_1">
                <bpmn:task id="Task_1"><bpmn:outgoing>Flow_1</bpmn:outgoing></bpmn:task>
                <bpmn:task id="Task_2"><bpmn:outgoing>Flow_2</bpmn:outgoing></bpmn:task>
                <bpmn:exclusiveGateway id="Gateway_1" name="Decision merged">
                  <bpmn:incoming>Flow_1</bpmn:incoming>
                  <bpmn:incoming>Flow_2</bpmn:incoming>
                  <bpmn:outgoing>Flow_3</bpmn:outgoing>
                </bpmn:exclusiveGateway>
                <bpmn:endEvent id="EndEvent_1"><bpmn:incoming>Flow_3</bpmn:incoming></bpmn:endEvent>
                <bpmn:sequenceFlow id="Flow_1" sourceRef="Task_1" targetRef="Gateway_1" />
                <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_2" targetRef="Gateway_1" />
                <bpmn:sequenceFlow id="Flow_3" sourceRef="Gateway_1" targetRef="EndEvent_1" />
              </bpmn:process>
            </bpmn:definitions>
            """.trimIndent()

        val issues = service.lint(xml, BpmnLintPhase.FINAL_POST_LAYOUT)
        assertNotNull(issues)
        issues!!

        val result = service.autoFix(xml, issues, BpmnLintPhase.FINAL_POST_LAYOUT)

        assertNotNull(result)
        result!!
        assertEquals(true, result.changed)
        assertEquals("Gateway_1", result.applied.single().elementId)
        assertTrue(!result.xml.contains("name=\"Decision merged\""))
        assertTrue(
            service.lint(result.xml, BpmnLintPhase.FINAL_POST_LAYOUT).orEmpty().none {
                it.rule == "klm/gtw-converging-gateway-unnamed"
            },
        )
    }

    @Test
    fun `init fails fast for unknown lint rule id`() {
        val service =
            BpmnLintService(
                catalogService = RuleCatalogService(),
                properties =
                    BpmnLintProperties(
                        rules = mapOf("klm/act-verb-object-name" to "error"),
                    ),
            )

        val exception =
            assertThrows<BpmnLintConfigurationException> {
                service.init()
            }

        assertTrue(exception.message!!.contains("klm/act-verb-object-name"), "Error message should contain the unknown rule id")
    }
}
