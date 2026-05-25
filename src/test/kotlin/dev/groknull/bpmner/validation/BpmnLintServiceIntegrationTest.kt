/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.validation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BpmnLintServiceIntegrationTest {
    @Test
    fun `lint returns issues for invalid BPMN XML using GraalJS bundle`() {
        val engine = BpmnLintJsEngine().apply { init() }
        val service =
            BpmnLintService(
                catalogService = RuleCatalogService(),
                engine = engine,
                pklAdapter = PklRuleCapabilityAdapter(RuleCatalogService()),
            )
        service.init()

        val invalidXml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions
                xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                id="Definitions_1"
                targetNamespace="http://example.com/bpmn">
              <bpmn:process id="Process_1" />
            </bpmn:definitions>
            """.trimIndent()

        val issues = service.lint(invalidXml)

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
    fun `lint detects duplicate diagram elements using plugin rule`() {
        val engine = BpmnLintJsEngine().apply { init() }
        val service =
            BpmnLintService(
                catalogService = RuleCatalogService(),
                engine = engine,
                pklAdapter = PklRuleCapabilityAdapter(RuleCatalogService()),
                properties =
                BpmnLintProperties(
                    extends = listOf("bpmnlint:recommended", "plugin:bpmner/recommended"),
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
              <bpmndi:BPMNDiagram id="Diagram_1">
                <bpmndi:BPMNPlane id="Plane_1" bpmnElement="Process_1" />
              </bpmndi:BPMNDiagram>
              <bpmndi:BPMNDiagram id="Diagram_2">
                <bpmndi:BPMNPlane id="Plane_2" bpmnElement="Process_1" />
              </bpmndi:BPMNDiagram>
            </bpmn:definitions>
            """.trimIndent()

        val issues = service.lint(duplicateDiagramXml)

        assertNotNull(issues)
        issues!!
        assertTrue(issues.any { it.rule == "bpmner/gen-no-duplicate-diagrams" }, "Should find duplicate diagram issue")
    }

    @Test
    fun `legacy duplicate diagram config and docs resolve through canonical rule`() {
        val engine = BpmnLintJsEngine().apply { init() }
        val service =
            BpmnLintService(
                catalogService = RuleCatalogService(),
                engine = engine,
                pklAdapter = PklRuleCapabilityAdapter(RuleCatalogService()),
                properties =
                BpmnLintProperties(
                    rules =
                    mapOf(
                        "bpmner/gen-no-duplicate-diagrams" to "off",
                        "bpmner/gen-02-no-duplicate-diagrams" to "error",
                    ),
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
              <bpmndi:BPMNDiagram id="Diagram_1">
                <bpmndi:BPMNPlane id="Plane_1" bpmnElement="Process_1" />
              </bpmndi:BPMNDiagram>
              <bpmndi:BPMNDiagram id="Diagram_2">
                <bpmndi:BPMNPlane id="Plane_2" bpmnElement="Process_1" />
              </bpmndi:BPMNDiagram>
            </bpmn:definitions>
            """.trimIndent()

        val issues = service.lint(duplicateDiagramXml).orEmpty()
        val docs = service.ruleDocs(listOf("bpmner/gen-02-no-duplicate-diagrams"))

        assertTrue(
            issues.any { it.rule == "bpmner/gen-no-duplicate-diagrams" },
            "Legacy config should report canonical rule",
        )
        assertTrue(
            issues.none { it.rule == "bpmner/gen-02-no-duplicate-diagrams" },
            "Legacy rule id should not leak into diagnostics",
        )
        assertTrue(docs["bpmner/gen-02-no-duplicate-diagrams"]?.contains("## Compatibility") == true)
    }

    @Test
    fun `autoFix clears named converging gateway using GraalJS bundle`() {
        val engine = BpmnLintJsEngine().apply { init() }
        val service =
            BpmnLintService(
                catalogService = RuleCatalogService(),
                engine = engine,
                pklAdapter = PklRuleCapabilityAdapter(RuleCatalogService()),
                properties =
                BpmnLintProperties(
                    extends = listOf("plugin:bpmner/recommended"),
                ),
            )
        service.init()

        val xml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions
                xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                id="Definitions_1"
                targetNamespace="http://example.com/bpmn">
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

        val issues = service.lint(xml)
        assertNotNull(issues)
        issues!!

        val result = service.autoFix(xml, issues)

        assertNotNull(result)
        result!!
        assertEquals(true, result.changed)
        assertEquals("Gateway_1", result.applied.single().elementId)
        assertTrue(!result.xml.contains("name=\"Decision merged\""))
        assertTrue(
            service.lint(result.xml).orEmpty().none {
                it.rule == "bpmner/gtw-converging-gateway-unnamed"
            },
        )
    }

    @Test
    fun `init fails fast for unknown lint rule id`() {
        val engine = BpmnLintJsEngine().apply { init() }
        val service =
            BpmnLintService(
                catalogService = RuleCatalogService(),
                engine = engine,
                pklAdapter = PklRuleCapabilityAdapter(RuleCatalogService()),
                properties =
                BpmnLintProperties(
                    rules = mapOf("bpmner/truly-unknown-rule" to "error"),
                ),
            )

        val exception =
            assertThrows<BpmnLintConfigurationException> {
                service.init()
            }

        assertTrue(
            exception.message!!.contains("bpmner/truly-unknown-rule"),
            "Error message should contain the unknown rule id",
        )
    }

    @Test
    fun `recommended-error preset excludes layout-dependent built-in rules`() {
        val engine = BpmnLintJsEngine().apply { init() }
        val service =
            BpmnLintService(
                catalogService = RuleCatalogService(),
                engine = engine,
                pklAdapter = PklRuleCapabilityAdapter(RuleCatalogService()),
                properties =
                BpmnLintProperties(
                    extends = listOf("plugin:bpmner/recommended-error"),
                ),
            )
        service.init()

        val activeRules = service.resolvedRules()

        // The bpmner pipeline produces semantic-only XML pre-layout. Upstream bpmnlint rules
        // `no-bpmndi` and `no-overlapping-elements` require BPMNDI to be present and would fire
        // on every element pre-layout. They must be excluded from any bpmner-owned preset.
        assertTrue(
            activeRules["no-bpmndi"] == null || activeRules["no-bpmndi"] == "off",
            "no-bpmndi must not be active under plugin:bpmner/recommended-error " +
                "(was: ${activeRules["no-bpmndi"]})",
        )
        assertTrue(
            activeRules["no-overlapping-elements"] == null ||
                activeRules["no-overlapping-elements"] == "off",
            "no-overlapping-elements must not be active under plugin:bpmner/recommended-error " +
                "(was: ${activeRules["no-overlapping-elements"]})",
        )
    }
}
