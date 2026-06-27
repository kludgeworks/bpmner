/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.pipeline.internal.adapter.inbound

/**
 * Thin seam for reading a yes/no opt-in from the user before showing a BPMN preview.
 *
 * A functional interface so production code can inject the real reader and tests can
 * inject a non-blocking fake. The implementation must be non-blocking in non-interactive
 * runs — returning `false` when no terminal is attached — so CI/test/piped runs are never
 * blocked on stdin. This opt-in is the sole gate before the preview is written and opened.
 *
 * Returns `true` if the user opted in (or on empty/blank input, which defaults to Yes),
 * `false` on an explicit No answer.
 */
fun interface PreviewPrompt {
    fun confirmOpenPreview(): Boolean
}
