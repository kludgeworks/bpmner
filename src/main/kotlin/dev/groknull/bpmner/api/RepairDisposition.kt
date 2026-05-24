/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.api

/**
 * Outcome classification for a repair attempt against a specific [RuleDiagnostic].
 *
 * Replaces the legacy `RepairKind` enum during the architecture migration.
 * Both coexist until Phase 4 completes the repair-loop integration.
 */
enum class RepairDisposition {
    /** A compiled (local) repair was applied and the diagnostic is resolved. */
    Applied,

    /** An LLM-driven repair resolved the diagnostic. */
    LlmResolved,

    /** The diagnostic could not be resolved by any available repair strategy. */
    Unresolved,

    /** No repair was attempted (e.g., the diagnostic is advisory-only). */
    NotApplicable,
}
