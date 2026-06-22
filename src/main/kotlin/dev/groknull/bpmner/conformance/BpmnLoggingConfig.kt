/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.conformance

import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * Artifact-logging configuration for the evaluation and authoring pipelines.
 *
 * Bound at `bpmner.logging` to preserve existing operator-facing property keys while placing
 * config ownership here (ADR-451 S4). The conformance module owns this config because
 * [BpmnEvaluationPipeline] is its primary consumer; the authoring module imports it via
 * its existing `conformance` grant.
 *
 * This config belongs here because moving it to `telemetry` would require a new
 * conformance→telemetry grant — a cycle, since `telemetry` already grants `conformance`.
 * `authoring` consumes this config via its existing `conformance` grant, making `conformance`
 * the only cycle-free home under the current module graph.
 */
@Validated
@ConfigurationProperties("bpmner.logging")
data class BpmnLoggingConfig(
    val dir: String = "logs",
    val dumpArtifacts: Boolean = false,
    @field:Min(1)
    val artifactPreviewLength: Int = DEFAULT_ARTIFACT_PREVIEW_LENGTH,
) {
    companion object {
        const val DEFAULT_ARTIFACT_PREVIEW_LENGTH = 8000
    }
}

/**
 * Conformance-module linting configuration.
 *
 * Bound at `bpmner` to preserve the existing `bpmner.lintBatchSize` property key
 * while placing config ownership in the conformance module (ADR-451 S4).
 */
@Validated
@ConfigurationProperties("bpmner")
data class BpmnConformanceConfig(
    @field:Min(1)
    val lintBatchSize: Int = DEFAULT_LINT_BATCH_SIZE,
) {
    companion object {
        const val DEFAULT_LINT_BATCH_SIZE = 10
    }
}
