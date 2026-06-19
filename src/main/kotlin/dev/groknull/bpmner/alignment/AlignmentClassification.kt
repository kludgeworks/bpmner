/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.alignment

enum class AlignmentClassification {
    SUPPORTED,
    ASSUMED,
    UNSUPPORTED,
    COVERED,
    PARTIALLY_COVERED,
    MISSING,
    EXTRA,
    CONTRADICTED,
}
