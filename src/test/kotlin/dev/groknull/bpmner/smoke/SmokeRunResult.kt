/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.smoke

/**
 * One row per test-method-instance, emitted by [SmokeResultRecorder] as a line of JSONL. Additive
 * test-only telemetry — it never affects whether a test passes.
 *
 * `attempts` and the authoritative final outcome are intentionally absent: Bazel reruns a fresh JVM
 * per `--flaky_test_attempts` retry, so the in-JVM writer always sees attempt 1; they are derived from
 * Bazel's `test.xml`/attempt dirs.
 */
data class SmokeRunResult(
    val ts: String,
    val runNumber: String?,
    val commit: String?,
    val branch: String?,
    val provider: String?,
    val testClass: String,
    val testMethod: String,
    val outcome: String,
    val failureCategory: String?,
    val failureSignal: String? = null,
    val message: String?,
    val failureSignature: String?,
    val failureHash: String?,
    val servedModel: String?,
    val costUsd: Double,
    val costKnown: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val llmCallCount: Int,
    val llmTimeMs: Long,
    val toolCallCount: Int,
    val stageBreakdown: Map<String, StageStats>,
    val roleBreakdown: Map<String, StageStats> = emptyMap(),
    val diagnostics: List<SmokeDiagnostic> = emptyList(),
    val diagnosticSummary: Map<String, Int> = emptyMap(),
    val testFingerprint: String,
    val promptFingerprint: String,
    val promptBaselineHash: String?,
    val embabelVersion: String?,
    val runComplete: Boolean,
)

/** Per-pipeline-stage model + token usage (keyed by `LlmInvocation.agentName`, e.g. readiness vs extraction). */
data class StageStats(
    val model: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val llmCalls: Int,
)

/** Aggregated diagnostic emitted by the smoke test harness for repeated parse/retry/validation issues. */
data class SmokeDiagnostic(
    val kind: String,
    val exceptionClass: String?,
    val messageSignature: String,
    val messageHash: String,
    val targetType: String?,
    val fieldPath: String?,
    val agentName: String?,
    val model: String?,
    val count: Int,
    val sample: String,
)
