/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.config

import dev.groknull.bpmner.alignment.internal.BpmnAlignmentThresholdsConfig
import dev.groknull.bpmner.authoring.internal.BpmnAuthoringBudgetConfig
import dev.groknull.bpmner.conformance.BpmnConformanceConfig
import dev.groknull.bpmner.conformance.BpmnLoggingConfig
import dev.groknull.bpmner.contract.internal.BpmnContractThresholdsConfig
import dev.groknull.bpmner.readiness.BpmnReadinessBudgetConfig
import dev.groknull.bpmner.readiness.BpmnReadinessThresholdsConfig
import dev.groknull.bpmner.repair.BpmnRepairBudgetConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest

/**
 * Binding coverage for the threshold, budget, and logging config classes after the S4
 * dissolution of `BpmnConfig`. Verifies that all existing `bpmner.*` property keys that
 * are NOT actor-persona keys still bind correctly to their new owning modules.
 *
 * Complements [BpmnConfigBindingTest] which covers actor-persona classes.
 * Satisfies exit gate 9 from PLAN-451-4 §6: all existing `bpmner.*` property keys bind.
 */
@SpringBootTest(
    classes = [BpmnConfigThresholdBindingTest.Config::class],
    properties = [
        "bpmner.readiness.ready-threshold=80",
        "bpmner.readiness.minimum-activity-count=3",
        "bpmner.readiness.max-clarification-questions=4",
        "bpmner.contract.max-assumptions=8",
        "bpmner.alignment.max-assumptions=2",
        "bpmner.alignment.block-on-unsupported-elements=false",
        "bpmner.alignment.block-on-missing-contract-items=false",
        "bpmner.budget.readiness=15",
        "bpmner.budget.generation=50",
        "bpmner.budget.max-repair-iterations=3",
        "bpmner.logging.dir=audit",
        "bpmner.logging.dump-artifacts=true",
        "bpmner.logging.artifact-preview-length=4000",
        "bpmner.lint-batch-size=5",
    ],
)
class BpmnConfigThresholdBindingTest {
    @EnableConfigurationProperties(
        BpmnReadinessThresholdsConfig::class,
        BpmnReadinessBudgetConfig::class,
        BpmnContractThresholdsConfig::class,
        BpmnAlignmentThresholdsConfig::class,
        BpmnAuthoringBudgetConfig::class,
        BpmnRepairBudgetConfig::class,
        BpmnLoggingConfig::class,
        BpmnConformanceConfig::class,
    )
    class Config

    @Autowired
    internal lateinit var readinessThresholds: BpmnReadinessThresholdsConfig

    @Autowired
    internal lateinit var readinessBudget: BpmnReadinessBudgetConfig

    @Autowired
    internal lateinit var contractThresholds: BpmnContractThresholdsConfig

    @Autowired
    internal lateinit var alignmentThresholds: BpmnAlignmentThresholdsConfig

    @Autowired
    internal lateinit var authoringBudget: BpmnAuthoringBudgetConfig

    @Autowired
    internal lateinit var repairBudget: BpmnRepairBudgetConfig

    @Autowired
    internal lateinit var loggingConfig: BpmnLoggingConfig

    @Autowired
    internal lateinit var conformanceConfig: BpmnConformanceConfig

    // bpmner.readiness.*
    @Test
    fun `bpmner-readiness-ready-threshold binds`() {
        assertEquals(80, readinessThresholds.readyThreshold)
    }

    @Test
    fun `bpmner-readiness-minimum-activity-count binds`() {
        assertEquals(3, readinessThresholds.minimumActivityCount)
    }

    @Test
    fun `bpmner-readiness-max-clarification-questions binds`() {
        assertEquals(4, readinessThresholds.maxClarificationQuestions)
    }

    // bpmner.contract.*
    @Test
    fun `bpmner-contract-max-assumptions binds`() {
        assertEquals(8, contractThresholds.maxAssumptions)
    }

    // bpmner.alignment.*
    @Test
    fun `bpmner-alignment-max-assumptions binds`() {
        assertEquals(2, alignmentThresholds.maxAssumptions)
    }

    @Test
    fun `bpmner-alignment-block-on-unsupported-elements binds`() {
        assertFalse(alignmentThresholds.blockOnUnsupportedElements)
    }

    @Test
    fun `bpmner-alignment-block-on-missing-contract-items binds`() {
        assertFalse(alignmentThresholds.blockOnMissingContractItems)
    }

    // bpmner.budget.*
    @Test
    fun `bpmner-budget-readiness binds`() {
        assertEquals(15, readinessBudget.readiness)
    }

    @Test
    fun `bpmner-budget-generation binds`() {
        assertEquals(50, authoringBudget.generation)
    }

    @Test
    fun `bpmner-budget-max-repair-iterations binds`() {
        assertEquals(3, repairBudget.maxRepairIterations)
    }

    // bpmner.logging.*
    @Test
    fun `bpmner-logging-dir binds`() {
        assertEquals("audit", loggingConfig.dir)
    }

    @Test
    fun `bpmner-logging-dump-artifacts binds`() {
        assertEquals(true, loggingConfig.dumpArtifacts)
    }

    @Test
    fun `bpmner-logging-artifact-preview-length binds`() {
        assertEquals(4000, loggingConfig.artifactPreviewLength)
    }

    // bpmner.lintBatchSize
    @Test
    fun `bpmner-lint-batch-size binds`() {
        assertEquals(5, conformanceConfig.lintBatchSize)
    }
}
