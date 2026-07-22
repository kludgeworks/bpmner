/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.conformance.ValidatedBpmnXml
import dev.groknull.bpmner.layout.internal.adapter.inbound.BpmnLayoutAgent
import dev.groknull.bpmner.ruleset.RuleEngine
import dev.groknull.bpmner.ruleset.RuleRegistry
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean

/**
 * Runs every retained-profile corpus `.bpmn` fixture through the real `BpmnLayoutPort`/
 * `BpmnLayoutAgent` Spring wiring, asserting the layout-then-validate call chain never throws.
 *
 * The corpus fixtures are hand-authored raw BPMN, so they are fed a placeholder [BpmnDefinition]
 * rather than a converted one; node/sequence-coverage checks against `bpmn.definition` are
 * exercised separately in `BpmnLayoutAgentTest`.
 */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES, verifyAutomatically = false)
@TestPropertySource(
    properties = [
        "embabel.agent.platform.models.anthropic.api-key=test-key",
        "embabel.agent.platform.models.openai.api-key=test-key",
        "embabel.agent.platform.models.gemini.api-key=test-key",
        "embabel.agent.platform.models.mistralai.api-key=test-key",
        "embabel.agent.platform.models.deepseek.api-key=test-key",
    ],
)
class BpmnLayoutPortCorpusIntegrationTest {
    @MockitoBean
    @Suppress("UnusedPrivateProperty") // Tier-2 mock for conformance adapters; not directly accessed
    private lateinit var ruleEngine: RuleEngine

    @MockitoBean
    @Suppress("UnusedPrivateProperty") // Tier-2 mock for conformance adapters; not directly accessed
    private lateinit var ruleRegistry: RuleRegistry

    @Autowired
    private lateinit var layoutAgent: BpmnLayoutAgent

    @ParameterizedTest(name = "layout + final validation succeeds for {0}")
    @ValueSource(
        strings = [
            "representative-process",
            "explicit-cycle",
            "annotation-and-group",
            "long-labels",
            "subprocess-flat",
            "subprocess-nested",
            "subprocess-branch",
            "subprocess-loop",
            "boundary-timer-task",
            "boundary-error-task",
            "boundary-multi",
            "boundary-on-subprocess",
            "subprocess-no-start-cycle",
            "subprocess-sequential-sharing",
            "collab-lanes",
            "collab-two-pools",
            "collab-blackbox",
            "collab-msg-endpoint",
            "collab-msg-label",
            "collab-subprocess",
            "collab-bioc",
            "collab-lanes-loopback",
            "miwg-a2-1",
            "miwg-a3-0",
        ],
    )
    fun `real BpmnLayoutPort bean lays out and validates every corpus fixture`(fixture: String) {
        val xml = load("layout-fixtures/$fixture.bpmn")
        val definition = BpmnDefinition(processId = fixture, processName = fixture, nodes = emptyList(), sequences = emptyList())

        assertDoesNotThrow {
            val laidOut = layoutAgent.layoutBpmnXml(ValidatedBpmnXml(definition, xml))
            layoutAgent.validateFinalBpmnXml(laidOut)
        }
    }

    private fun load(resource: String): String = javaClass.classLoader.getResourceAsStream(resource)
        ?.use { it.readBytes().toString(Charsets.UTF_8) }
        ?: error("Resource not found: $resource")
}
