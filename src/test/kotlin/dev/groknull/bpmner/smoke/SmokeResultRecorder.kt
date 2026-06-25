/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.smoke

import com.embabel.agent.core.LlmInvocation
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestWatcher
import org.slf4j.LoggerFactory
import org.springframework.test.context.junit.jupiter.SpringExtension
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.Optional

/**
 * Records one [SmokeRunResult] per test method — outcome, per-test cost/usage (from
 * [PerTestEventCapture]), change-detection fingerprints, and CI identity — and writes them as JSONL to
 * Bazel's `TEST_UNDECLARED_OUTPUTS_DIR` at suite end (collected into the test's `outputs.zip`).
 * Additive: it never affects whether a test passes.
 *
 * Resets [PerTestEventCapture] before each test (`beforeEach`) and snapshots it when the outcome is
 * known (`TestWatcher`).
 */
@Suppress("TooManyFunctions") // test-only recorder; several small cohesive extraction/fingerprint helpers
class SmokeResultRecorder :
    BeforeEachCallback,
    TestWatcher,
    AfterAllCallback {
    private val logger = LoggerFactory.getLogger(SmokeResultRecorder::class.java)
    private val rows = mutableListOf<SmokeRunResult>()

    override fun beforeEach(context: ExtensionContext) {
        capture(context)?.reset()
    }

    override fun testSuccessful(context: ExtensionContext) = record(context, outcome = "pass", category = null, message = null)

    override fun testFailed(
        context: ExtensionContext,
        cause: Throwable,
    ) = record(
        context,
        outcome = "fail",
        category = smokeCategoryFor(cause),
        message = cause.message,
        cause = cause,
    )

    override fun testAborted(
        context: ExtensionContext,
        cause: Throwable,
    ) = record(context, outcome = "skip", category = "infra", message = cause.message)

    override fun testDisabled(
        context: ExtensionContext,
        reason: Optional<String>,
    ) = record(context, outcome = "skip", category = null, message = reason.orElse(null))

    override fun afterAll(context: ExtensionContext) {
        if (rows.isEmpty()) return
        val mapper = bean(context, ObjectMapper::class.java) ?: ObjectMapper()
        val target = outputDir().resolve(JSONL_FILE)
        try {
            Files.createDirectories(target.parent)
            val lines = rows.map { mapper.writeValueAsString(it.copy(runComplete = true)) }
            Files.write(target, lines, Charsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            logger.info("Wrote {} smoke result rows to {}", lines.size, target)
        } catch (e: java.io.IOException) {
            logger.warn("Failed to write smoke results to {}", target, e)
        }
    }

    private fun record(
        context: ExtensionContext,
        outcome: String,
        category: String?,
        message: String?,
        cause: Throwable? = null,
    ) {
        val snap = capture(context)?.snapshot()
        val llmCallCount = snap?.llmCallCount ?: 0
        val diagnostics = enrichDiagnosticModels(SmokeDiagnosticCapture.snapshot(), snap)
        val testMethod = context.displayName
        val signature = failureSignature(outcome, testMethod, message)
        rows.add(
            SmokeRunResult(
                ts = Instant.now().toString(),
                runNumber = env("GITHUB_RUN_NUMBER"),
                commit = env("GITHUB_SHA"),
                branch = env("GITHUB_HEAD_REF") ?: env("GITHUB_REF"),
                provider = env("SPRING_PROFILES_ACTIVE"),
                testClass = context.testClass.map { it.simpleName }.orElse("unknown"),
                testMethod = testMethod,
                outcome = outcome,
                failureCategory = category,
                failureSignal = smokeFailureSignalFor(outcome, cause, llmCallCount),
                message = message?.take(MAX_MESSAGE),
                failureSignature = signature,
                failureHash = signature?.let { sha256(it.toByteArray(Charsets.UTF_8)) },
                servedModel = snap?.servedModel,
                costUsd = snap?.costUsd ?: 0.0,
                costKnown = snap?.costKnown ?: "unknown",
                promptTokens = snap?.promptTokens ?: 0,
                completionTokens = snap?.completionTokens ?: 0,
                llmCallCount = llmCallCount,
                llmTimeMs = snap?.llmTimeMs ?: 0,
                toolCallCount = snap?.toolCallCount ?: 0,
                stageBreakdown = snap?.stageBreakdown ?: emptyMap(),
                roleBreakdown = snap?.roleBreakdown ?: emptyMap(),
                diagnostics = diagnostics,
                diagnosticSummary = diagnostics.groupingBy { it.kind }.fold(0) { total, diagnostic -> total + diagnostic.count },
                testFingerprint = testClassFingerprint(context),
                promptFingerprint = cachedPromptFingerprint,
                promptBaselineHash = cachedPromptBaselineHash,
                embabelVersion = LlmInvocation::class.java.`package`?.implementationVersion,
                runComplete = false,
            ),
        )
    }

    private fun capture(context: ExtensionContext): PerTestEventCapture? = bean(context, PerTestEventCapture::class.java)

    private fun enrichDiagnosticModels(
        diagnostics: List<SmokeDiagnostic>,
        snap: PerTestEventCapture.Capture?,
    ): List<SmokeDiagnostic> {
        if (snap == null) return diagnostics
        return diagnostics.map { diagnostic ->
            val model = diagnostic.model ?: modelForDiagnostic(diagnostic, snap)
            if (model == null) diagnostic else diagnostic.copy(model = model)
        }
    }

    private fun modelForDiagnostic(
        diagnostic: SmokeDiagnostic,
        snap: PerTestEventCapture.Capture,
    ): String? {
        diagnostic.agentName?.let { agentName ->
            snap.stageBreakdown[agentName]?.model?.let { return it }
            snap.stageBreakdown.entries.firstOrNull { (stage, _) ->
                agentName.contains(stage) || stage.contains(agentName)
            }?.value?.model?.let { return it }
        }
        return snap.servedModel
    }

    private fun <T : Any> bean(
        context: ExtensionContext,
        type: Class<T>,
    ): T? = runCatching { SpringExtension.getApplicationContext(context).getBean(type) }.getOrNull()

    private fun failureSignature(
        outcome: String,
        method: String,
        message: String?,
    ): String? = if (outcome != "fail") {
        null
    } else {
        "$method::${message?.lineSequence()?.firstOrNull()?.trim()?.take(MAX_SIGNATURE).orEmpty()}"
    }

    // Cached — the test class is the same for every method in a suite. The test SOURCE is not in the
    // Bazel sandbox, so hash the compiled class bytecode as a proxy (stable within a build, changes
    // when the test changes).
    private var cachedTestFingerprint: String? = null

    private fun testClassFingerprint(context: ExtensionContext): String {
        cachedTestFingerprint?.let { return it }
        val cls = context.testClass.orElse(null) ?: return UNKNOWN
        val res = cls.getResource("${cls.simpleName}.class")
        val fp = if (res == null) UNKNOWN else runCatching { sha256(res.readBytes()) }.getOrDefault(UNKNOWN)
        return fp.also { cachedTestFingerprint = it }
    }

    // Cached: prompts don't change during a suite. UNKNOWN unless *every* prompt resolves, so a partial
    // hash (some resources absent) never masquerades as a real, comparable fingerprint.
    private val cachedPromptFingerprint: String by lazy {
        val cl = SmokeResultRecorder::class.java.classLoader
        val resolved = VOCAB_PROMPTS.sorted().map { cl.getResource(it) }
        if (resolved.any { it == null }) {
            UNKNOWN
        } else {
            sha256(resolved.filterNotNull().fold(ByteArray(0)) { acc, url -> acc + url.readBytes() })
        }
    }

    private val cachedPromptBaselineHash: String? by lazy {
        SmokeResultRecorder::class.java.classLoader.getResource(PROMPT_BASELINES)?.let { url ->
            runCatching { sha256(url.readBytes()) }.getOrNull()
        }
    }

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    private fun outputDir(): Path = Path.of(
        env("TEST_UNDECLARED_OUTPUTS_DIR")
            ?: env("TEST_TMPDIR")
            ?: System.getProperty("java.io.tmpdir"),
    )

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val JSONL_FILE = "smoke-results.jsonl"
        const val MAX_MESSAGE = 2000
        const val MAX_SIGNATURE = 160
        const val UNKNOWN = "unknown"
        const val PROMPT_BASELINES = "prompt-baselines.json"
        val VOCAB_PROMPTS =
            listOf(
                "prompts/bpmner/assess_readiness.jinja",
                "prompts/bpmner/extract_contract.jinja",
                "prompts/bpmner/elements/activity_kinds.jinja",
                "prompts/bpmner/elements/actors.jinja",
                "prompts/bpmner/elements/branch_kinds.jinja",
                "prompts/bpmner/elements/data_artifacts.jinja",
                "prompts/bpmner/elements/end_state_kinds.jinja",
                "prompts/bpmner/elements/subprocesses.jinja",
                "prompts/bpmner/elements/topology_rules.jinja",
            )
    }
}

internal const val SMOKE_FAILURE_SIGNAL_NO_SIGNAL = "no_signal"

// A failed extraction assertion is a `classification` miss; a timeout / transport error is `infra`.
// Zero-call quota/account-balance failures are also kept out of `deterministic` for legacy consumers;
// [smokeFailureSignalFor] provides the new explicit machine-readable no-signal marker.
internal fun smokeCategoryFor(cause: Throwable): String = when {
    isSmokeQuotaOrBillingFailure(cause) || isSmokeInfraFailure(cause) -> "infra"
    cause is AssertionError -> "classification"
    else -> "deterministic"
}

internal fun smokeFailureSignalFor(
    outcome: String,
    cause: Throwable?,
    llmCallCount: Int,
): String? = if (isZeroCallQuotaFailure(outcome, cause, llmCallCount)) {
    SMOKE_FAILURE_SIGNAL_NO_SIGNAL
} else {
    null
}

private fun isZeroCallQuotaFailure(
    outcome: String,
    cause: Throwable?,
    llmCallCount: Int,
): Boolean = outcome == "fail" && llmCallCount == 0 && cause?.let(::isSmokeQuotaOrBillingFailure) == true

internal fun isSmokeQuotaOrBillingFailure(cause: Throwable): Boolean = generateSequence(cause) { it.cause }.any { throwable ->
    val message = throwable.message.orEmpty()
    val normalized = message.lowercase()
    QUOTA_OR_BILLING_SIGNATURES.any { it in normalized } ||
        HTTP_QUOTA_CODES.any { it in message } ||
        isLowCreditInvalidRequest(message, normalized)
}

private fun isLowCreditInvalidRequest(
    message: String,
    normalized: String,
): Boolean = "400" in message && "invalid_request_error" in normalized && "credit" in normalized

private fun isSmokeInfraFailure(cause: Throwable): Boolean = generateSequence(cause) { it.cause }.any {
    isSmokeInfraTypeName(it::class.java.name) || "timed out" in (it.message ?: "").lowercase()
}

private fun isSmokeInfraTypeName(name: String): Boolean = name.startsWith("java.net.") ||
    name.startsWith("java.util.concurrent.") ||
    "TimeoutException" in name

private val HTTP_QUOTA_CODES = listOf("429")

private val QUOTA_OR_BILLING_SIGNATURES = listOf(
    "too many requests",
    "rate limit",
    "rate_limit_error",
    "quota",
    "overloaded_error",
    "credit balance",
    "credits depleted",
    "insufficient credit",
    "insufficient funds",
    "billing",
    "account balance",
)
