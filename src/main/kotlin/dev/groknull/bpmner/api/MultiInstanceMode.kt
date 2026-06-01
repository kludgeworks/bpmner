/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.api

/**
 * Execution mode for a multi-instance activity: each item handled one after another
 * (`SEQUENTIAL`) or all items concurrently (`PARALLEL`). Maps to the BPMN 2.0
 * `multiInstanceLoopCharacteristics/@isSequential` attribute (`true` / `false`).
 *
 * Shared across the api/core BPMN model, the LLM-facing flat wire types, and the process
 * contract so a contract activity's iteration mode compares directly to the rendered task's.
 */
enum class MultiInstanceMode {
    SEQUENTIAL,
    PARALLEL,
}
