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
