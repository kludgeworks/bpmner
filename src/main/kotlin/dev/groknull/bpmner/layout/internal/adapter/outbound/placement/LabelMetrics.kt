/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound.placement

/**
 * Frozen glyph advance-width table for the bpmn-js default label font.
 *
 * **Font contract:** exact for `font = "12px Arial, sans-serif"` — the font and size
 * bpmn-js uses for external labels (via diagram-js Text). If the bpmn-js default label
 * font or size changes, regenerate the table by running the Playwright capture script
 * documented in `plans/557/ARCHITECTURE.md §AD-557-15`.
 *
 * **Provenance:** each entry is `canvas.getContext('2d').measureText(char).width`
 * at `font = "12px Arial, sans-serif"` captured in real Chromium (the same engine
 * goldens are blessed against) via Playwright. Shared data file:
 * `src/test/resources/label-metrics.json`.
 *
 * **Non-ASCII fallback:** any character outside the ASCII printable range (32..126)
 * uses [DEFAULT_ADVANCE] — a mid-width conservative estimate that over-reserves
 * rather than clips. This is a known gap; the retained corpus is ASCII-only.
 *
 * **Canvas advance-sum model:** canvas `measureText` sums glyph advances linearly for
 * ASCII Arial with no kerning adjustments, so `width(str) = Σ advance(char)` matches
 * the oracle to sub-pixel accuracy.
 */
internal object LabelMetrics {

    /** Advance width for any character outside the ASCII printable range. */
    internal const val DEFAULT_ADVANCE = 6.673828125

    /**
     * Line height for bpmn-js external labels.
     *
     * diagram-js `getLineHeight` returns `style.lineHeight × fontSize` only when both
     * style keys are set. The bpmn-js external-label style does not set `lineHeight`,
     * so diagram-js falls back to per-line measured box height. The single-line box
     * height of "Ag" at `12px Arial, sans-serif` measured in real Chromium is 14.
     */
    internal const val LINE_HEIGHT = 14.0

    // Dense array indexed by codepoint 32..126 (ASCII printable range).
    // Values are measureText(char).width at "12px Arial, sans-serif" in real Chromium.
    // Ordered: space(32), '!'(33), '"'(34), ..., '~'(126).
    @Suppress("MagicNumber")
    private val ADVANCE = doubleArrayOf(
        3.333984375, 3.333984375, 4.259765625, 6.673828125, 6.673828125,
        10.669921875, 8.00390625, 2.291015625, 3.99609375, 3.99609375,
        4.669921875, 7.0078125, 3.333984375, 3.99609375, 3.333984375,
        3.333984375, 6.673828125, 6.673828125, 6.673828125, 6.673828125,
        6.673828125, 6.673828125, 6.673828125, 6.673828125, 6.673828125,
        6.673828125, 3.333984375, 3.333984375, 7.0078125, 7.0078125,
        7.0078125, 6.673828125, 12.181640625, 8.00390625, 8.00390625,
        8.666015625, 8.666015625, 8.00390625, 7.330078125, 9.333984375,
        8.666015625, 3.333984375, 6.0, 8.00390625, 6.673828125,
        9.99609375, 8.666015625, 9.333984375, 8.00390625, 9.333984375,
        8.666015625, 8.00390625, 7.330078125, 8.666015625, 8.00390625,
        11.326171875, 8.00390625, 8.00390625, 7.330078125, 3.333984375,
        3.333984375, 3.333984375, 5.630859375, 6.673828125, 3.99609375,
        6.673828125, 6.673828125, 6.0, 6.673828125, 6.673828125,
        3.333984375, 6.673828125, 6.673828125, 2.666015625, 2.666015625,
        6.0, 2.666015625, 9.99609375, 6.673828125, 6.673828125,
        6.673828125, 6.673828125, 3.99609375, 6.0, 3.333984375,
        6.673828125, 6.0, 8.666015625, 6.0, 6.0,
        6.0, 4.0078125, 3.1171875, 4.0078125, 7.0078125,
    )

    private const val ADVANCE_BASE = 32
    private const val ADVANCE_MAX = 126

    /** Advance width for a single character. */
    internal fun advance(ch: Char): Double {
        val cp = ch.code
        return if (cp in ADVANCE_BASE..ADVANCE_MAX) ADVANCE[cp - ADVANCE_BASE] else DEFAULT_ADVANCE
    }

    /**
     * Total advance width for [text], reproducing `measureText(text).width`
     * at `12px Arial, sans-serif` in Chromium for ASCII text.
     *
     * Empty string returns 0.0. Trailing whitespace is NOT stripped here;
     * callers that need strip-trailing-space behaviour (e.g. [LabelWrap]) handle
     * it themselves, matching diagram-js's `getTextBBox` contract.
     */
    internal fun width(text: String): Double = text.sumOf { advance(it) }
}
