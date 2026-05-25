/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.api

/**
 * Stringly-typed diagnostic code alias.
 *
 * Severity overrides and repair dispositions key on [DiagnosticCode] rather than
 * rule ID, because a single rule may emit multiple distinct codes (e.g.,
 * `EventDefinitionRule` emits both `"def-missing-event-def"` and `"def-invalid-message-ref"`).
 */
typealias DiagnosticCode = String
