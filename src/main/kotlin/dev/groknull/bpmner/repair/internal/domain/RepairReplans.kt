/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain

/**
 * Bridge to the Embabel framework's replan signal. Called when a cycle is detected,
 * progress stalls, or an LLM operation produces unparseable/invalid output.
 */
object RepairReplans {
    // `ReplanRequestedException` doesn't expose a cause slot in its constructor, so chain via
    // `initCause`. Preserving the cause lets a downstream operator trace the framework exception
    // (`InvalidLlmReturnFormatException` etc.) without grepping logs.
    fun signal(reason: String, cause: Throwable? = null): RuntimeException {
        val ex = com.embabel.agent.core.ReplanRequestedException(reason)
        if (cause != null) ex.initCause(cause)
        return ex
    }
}

// Not a ReplanRequestedException subtype — that framework class is `final` — but
// revalidateAndAdvance is reached by all three repair tiers (label patch, structural patch, full
// rewrite), so BpmnRepairLoop.selectAndApply's outer catch must handle this alongside
// ReplanRequestedException regardless of which tier stalled; see its catch clause.
internal class StuckBlockingDiagnosticsException(
    message: String,
) : RuntimeException(message)

// Distinct from the generic "unchanged patch" no-progress signal: the LLM's patch DID change
// something, but the conformance pass stamped the result straight back to the prior state — the
// edit never survived far enough to be judged, which is a different failure shape from an LLM
// that produced no change at all. Escalated to a full rewrite by BpmnRepairLoop for the
// structural tier; see its catch clauses.
internal class NoEffectiveProgressException(
    message: String,
) : RuntimeException(message)
