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
    val testFingerprint: String,
    val promptFingerprint: String,
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
