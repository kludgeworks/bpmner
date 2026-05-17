/*
 * Copyright (c) 2026 The Project Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
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

@SpringBootTest(classes = [BpmnConfigBindingTest.Config::class])
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
