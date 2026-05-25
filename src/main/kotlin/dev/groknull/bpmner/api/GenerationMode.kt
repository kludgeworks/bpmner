/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.api

/**
 * Whether a generation request runs once with no human-in-the-loop ([SINGLE_SHOT]) or may
 * pause to ask clarification questions before producing output ([INTERACTIVE]).
 */
enum class GenerationMode {
    SINGLE_SHOT,
    INTERACTIVE,
}
