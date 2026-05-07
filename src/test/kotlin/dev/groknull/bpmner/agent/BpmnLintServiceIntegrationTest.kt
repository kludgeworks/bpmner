package dev.groknull.bpmner.agent

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BpmnLintServiceIntegrationTest {

    @Test
    fun `lint returns issues for invalid bpmn xml using GraalJS`() {
        val service = BpmnLintService()
        service.init()

        val invalidXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1">
              <bpmn:process id="Process_1" />
            </bpmn:definitions>
        """.trimIndent()

        val issues = service.lint(invalidXml)
        
        assertNotNull(issues, "Issues should not be null when linting is functional")
        // We expect at least start-event-required or end-event-required
        assertTrue(issues.isNotEmpty(), "Should find issues in minimal process")
        assertTrue(issues.any { it.rule == "start-event-required" || it.rule == "end-event-required" }, "Should find expected core issues. Found: ${issues.map { it.rule }}")
    }
    
    @Test
    fun `lint detects duplicate diagram elements using KLM rule`() {
        val service = BpmnLintService(
            BpmnLintProperties(
                extends = listOf("bpmnlint:recommended", "plugin:klm/recommended"),
            )
        )
        service.init()

        val duplicateDiagramXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" 
              xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" 
              id="Definitions_1" targetNamespace="http://example.com/bpmn">
              <bpmn:process id="Process_1" />
              <bpmndi:BPMNDiagram id="Diagram_1"><bpmndi:BPMNPlane id="Plane_1" bpmnElement="Process_1" /></bpmndi:BPMNDiagram>
              <bpmndi:BPMNDiagram id="Diagram_2"><bpmndi:BPMNPlane id="Plane_2" bpmnElement="Process_1" /></bpmndi:BPMNDiagram>
            </bpmn:definitions>
        """.trimIndent()

        val issues = service.lint(duplicateDiagramXml)
        
        assertNotNull(issues)
        assertTrue(issues.any { it.rule == "klm/gen-02-no-duplicate-diagrams" }, "Should find KLM duplicate diagram issue")
    }
}
