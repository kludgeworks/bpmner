/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.config

import com.embabel.agent.api.common.Actor
import com.embabel.agent.prompt.persona.Persona
import com.embabel.common.ai.model.ByRoleModelSelectionCriteria
import com.google.devtools.build.runfiles.Runfiles
import dev.groknull.bpmner.alignment.internal.BpmnAlignmentConfig
import dev.groknull.bpmner.authoring.internal.BpmnAuthoringConfig
import dev.groknull.bpmner.contract.internal.BpmnContractConfig
import dev.groknull.bpmner.readiness.BpmnReadinessConfig
import dev.groknull.bpmner.repair.BpmnRepairConfig
import dev.groknull.bpmner.ruleset.internal.BpmnRulesConfig
import dev.groknull.bpmner.ruleset.internal.BpmnRulesUriConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import java.io.File
import kotlin.test.assertIs

/**
 * Binding coverage for the capability-owned config classes after the S4 dissolution of
 * `BpmnConfig`. Verifies that all existing `bpmner.*` property keys still bind to their
 * new owning modules, and that actor personas carry the correct roles.
 */
@SpringBootTest(
    classes = [BpmnConfigBindingTest.Config::class],
    properties = ["bpmner.rules.config-uri=file:/tmp/team-bpmner.pkl"],
)
class BpmnConfigBindingTest {
    @EnableConfigurationProperties(
        BpmnReadinessConfig::class,
        BpmnContractConfig::class,
        BpmnAlignmentConfig::class,
        BpmnRulesConfig::class,
        BpmnRulesUriConfig::class,
        BpmnAuthoringConfig::class,
        BpmnRepairConfig::class,
    )
    class Config

    @Autowired
    internal lateinit var readinessConfig: BpmnReadinessConfig

    @Autowired
    internal lateinit var contractConfig: BpmnContractConfig

    @Autowired
    internal lateinit var alignmentConfig: BpmnAlignmentConfig

    @Autowired
    internal lateinit var rulesConfig: BpmnRulesConfig

    @Autowired
    internal lateinit var rulesUriConfig: BpmnRulesUriConfig

    @Autowired
    internal lateinit var authoringConfig: BpmnAuthoringConfig

    @Autowired
    internal lateinit var repairConfig: BpmnRepairConfig

    @Test
    fun `generator actor binds to BPMN Designer persona with generator role`() {
        assertActorRole(authoringConfig.generator, persona = "BPMN Designer", role = "generator")
    }

    @Test
    fun `labelRepairer actor binds to BPMN Label Copy Editor persona with repair-label role`() {
        assertActorRole(repairConfig.labelRepairer, persona = "BPMN Label Copy Editor", role = "repair-label")
    }

    @Test
    fun `patchRepairer actor binds to BPMN Patch Repair Specialist persona with repair-patch role`() {
        assertActorRole(repairConfig.patchRepairer, persona = "BPMN Patch Repair Specialist", role = "repair-patch")
    }

    @Test
    fun `rewriteRepairer actor binds to BPMN Full Rewrite Specialist persona with repair-rewrite role`() {
        assertActorRole(repairConfig.rewriteRepairer, persona = "BPMN Full Rewrite Specialist", role = "repair-rewrite")
    }

    @Test
    fun `legacy repairer actor still binds for backward compatibility`() {
        assertActorRole(repairConfig.repairer, persona = "BPMN Repair Specialist", role = "repairer")
    }

    @Test
    fun `readinessAssessor actor binds to BPMN Readiness Assessor persona`() {
        assertActorRole(readinessConfig.readinessAssessor, persona = "BPMN Readiness Assessor", role = "readiness-assessor")
    }

    @Test
    fun `contractExtractor actor binds to Process Contract Extractor persona`() {
        assertActorRole(contractConfig.contractExtractor, persona = "Process Contract Extractor", role = "contract-extractor")
    }

    @Test
    fun `alignmentValidator actor binds to BPMN Alignment Guard persona`() {
        assertActorRole(alignmentConfig.alignmentValidator, persona = "BPMN Alignment Guard", role = "alignment-validator")
    }

    @Test
    fun `linter actor binds to BPMN Linter persona`() {
        assertActorRole(rulesConfig.linter, persona = "BPMN Linter", role = "lint")
    }

    @Test
    fun `rules config-uri binds for team bpmner pkl override`() {
        assertEquals("file:/tmp/team-bpmner.pkl", rulesUriConfig.configUri)
    }

    /**
     * Static regression guard for the epic #592 native-enforcement assessment
     * ([dev.groknull.bpmner.llm.defaultRoleLlmOptions]'s KDoc): all five roles stay on
     * Embabel's implicit `NativeStructuredOutputMode.DEFAULT` (native output where supported,
     * verified fallback otherwise). 1.0.0-RC1's `LlmOptions` exposes no inspectable
     * native-structured-output field to assert against directly (verified via its public API),
     * so this asserts the compile-time-visible equivalent: no `src/main` call site explicitly
     * opts a role into `NATIVE`/`DISABLED` via `.withNativeStructuredOutput(...)`, which would
     * silently regress a role off the fallback-safe default this assessment relies on.
     */
    @Test
    fun `no role opts into an explicit native structured output mode`() {
        val runfiles = Runfiles.preload().withSourceRepository("")
        val anchor = runfiles.rlocation("bpmner/src/main/kotlin/dev/groknull/bpmner/llm/RoleLlmOptions.kt")
            ?: runfiles.rlocation("_main/src/main/kotlin/dev/groknull/bpmner/llm/RoleLlmOptions.kt")
            ?: runfiles.rlocation("src/main/kotlin/dev/groknull/bpmner/llm/RoleLlmOptions.kt")
        assertTrue(anchor != null, "Could not resolve RoleLlmOptions.kt via runfiles")

        var mainKotlinRoot = File(anchor).parentFile
        while (mainKotlinRoot.name != "kotlin") {
            mainKotlinRoot = mainKotlinRoot.parentFile
                ?: error("Could not find 'kotlin' root above $anchor")
        }

        val offendingFiles = mainKotlinRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                file.readLines().any { line ->
                    val code = line.trim()
                    !code.startsWith("*") && !code.startsWith("//") && code.contains(".withNativeStructuredOutput(")
                }
            }
            .map { it.name }
            .toList()

        assertTrue(
            offendingFiles.isEmpty(),
            "Found src/main call site(s) opting into an explicit native structured output mode: $offendingFiles",
        )
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

    @Test
    fun `BpmnRulesConfig data class methods run successfully`() {
        val config1 = BpmnRulesConfig()
        val config2 = BpmnRulesConfig()

        assertEquals(config1, config2)
        assertEquals(config1.hashCode(), config2.hashCode())
        assertEquals(config1.toString(), config2.toString())

        val config3 = config1.copy(linter = BpmnRulesConfig.DEFAULT_LINTER)
        assertEquals(config1, config3)
    }

    @Test
    fun `BpmnRulesUriConfig data class methods run successfully`() {
        val config1 = BpmnRulesUriConfig(configUri = "file:/tmp/foo.pkl")
        val config2 = BpmnRulesUriConfig(configUri = "file:/tmp/foo.pkl")
        val config3 = BpmnRulesUriConfig(configUri = "file:/tmp/bar.pkl")

        assertEquals(config1, config2)
        assertEquals(config1.hashCode(), config2.hashCode())
        assertEquals(config1.toString(), config2.toString())
        assertNotEquals(config1, config3)

        val config4 = config1.copy(configUri = "file:/tmp/bar.pkl")
        assertEquals(config3, config4)
    }
}
