/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.core

import com.embabel.agent.api.common.Actor
import com.embabel.agent.prompt.persona.Persona
import com.embabel.common.ai.model.ByRoleModelSelectionCriteria
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertIs

@SpringBootTest(
    classes = [BpmnConfigBindingTest.Config::class],
    properties = ["bpmner.rules.config-uri=file:/tmp/team-bpmner.pkl"],
)
class BpmnConfigBindingTest {
    @EnableConfigurationProperties(BpmnConfig::class)
    class Config

    @Autowired
    lateinit var config: BpmnConfig

    @Test
    fun `generator actor binds to BPMN Designer persona with generator role`() {
        assertActorRole(config.generator, persona = "BPMN Designer", role = "generator")
    }

    @Test
    fun `labelRepairer actor binds to BPMN Label Copy Editor persona with repair-label role`() {
        assertActorRole(config.labelRepairer, persona = "BPMN Label Copy Editor", role = "repair-label")
    }

    @Test
    fun `patchRepairer actor binds to BPMN Patch Repair Specialist persona with repair-patch role`() {
        assertActorRole(config.patchRepairer, persona = "BPMN Patch Repair Specialist", role = "repair-patch")
    }

    @Test
    fun `rewriteRepairer actor binds to BPMN Full Rewrite Specialist persona with repair-rewrite role`() {
        assertActorRole(config.rewriteRepairer, persona = "BPMN Full Rewrite Specialist", role = "repair-rewrite")
    }

    @Test
    fun `legacy repairer actor still binds for backward compatibility`() {
        assertActorRole(config.repairer, persona = "BPMN Repair Specialist", role = "repairer")
    }

    @Test
    fun `rules config-uri binds for team bpmner pkl override`() {
        assertEquals("file:/tmp/team-bpmner.pkl", config.rules.configUri)
    }

    private fun assertActorRole(
        actor: Actor<Persona>,
        persona: String,
        role: String,
    ) {
        assertEquals(persona, actor.persona.name, "unexpected persona name for role '$role'")
        val criteria =
            assertIs<ByRoleModelSelectionCriteria>(
                actor.llm.criteria,
                "expected ByRoleModelSelectionCriteria for role '$role'",
            )
        assertEquals(role, criteria.role, "unexpected role for persona '$persona'")
    }
}
