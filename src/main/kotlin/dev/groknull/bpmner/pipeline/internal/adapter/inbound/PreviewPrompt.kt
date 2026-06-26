/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.pipeline.internal.adapter.inbound

/**
 * Thin seam for reading a yes/no opt-in from the user before showing a BPMN preview.
 *
 * A functional interface so production code can inject the real reader and tests can
 * inject a non-blocking fake. The caller is responsible for gating on
 * [dev.groknull.bpmner.browser.InteractiveEnvironment.canOpenBrowser] before invoking
 * this prompt so that non-interactive/CI/test runs are never blocked on stdin.
 *
 * Returns `true` if the user opted in (or on empty/blank input, which defaults to Yes),
 * `false` on an explicit No answer.
 */
fun interface PreviewPrompt {
    fun confirmOpenPreview(): Boolean
}
