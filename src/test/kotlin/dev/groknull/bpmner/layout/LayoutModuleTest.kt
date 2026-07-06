/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout

import dev.groknull.bpmner.ruleset.RuleEngine
import dev.groknull.bpmner.ruleset.RuleRegistry
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean

/**
 * Validates that the `layout` module context bootstraps and exposes its root-package ports.
 *
 * BootstrapMode.DIRECT_DEPENDENCIES (ADR-009 (bootstrap tiers) Tier 2): `layout` grants only `bpmn` and
 * `conformance`; `BpmnLayoutService` has no cross-module Spring-bean dependencies of its own.
 * The `conformance` adapters constructor-inject two `ruleset` `@PrimaryPort` interfaces
 * (`RuleEngine`, `RuleRegistry`) that are outside the DIRECT closure — `ruleset` is
 * `conformance`'s dependency, not `layout`'s. Since `layout` never references either port
 * directly (zero `import dev.groknull.bpmner.ruleset` under `layout/`), both are mocked here
 * as Tier-2 transitive non-collaborators (ADR-009 (bootstrap tiers) lines 955–964, 968–971).
 * No `@EnableAgents` is needed: no bean in {layout, bpmn, conformance} injects `AgentPlatform`.
 * API keys are stubbed so no live LLM call is made at startup.
 * (S7 — ADR-009 (bootstrap tiers); ARCHITECTURE §5 S7)
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
class LayoutModuleTest {
    @MockitoBean
    @Suppress("UnusedPrivateProperty") // Tier-2 mock for conformance adapters; not directly accessed
    private lateinit var ruleEngine: RuleEngine

    @MockitoBean
    @Suppress("UnusedPrivateProperty") // Tier-2 mock for conformance adapters; not directly accessed
    private lateinit var ruleRegistry: RuleRegistry

    @Autowired
    private lateinit var bpmnLayoutPort: BpmnLayoutPort

    @Test
    fun `layout module bootstraps and exposes its layout port`() {
        assertNotNull(bpmnLayoutPort, "BpmnLayoutPort should be available in the layout module context")
    }
}
