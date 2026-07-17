/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound.placement

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Frozen glyph advance-width table for the bpmn-js default label font.
 *
 * bpmn-js is the renderer; label wrapping in the JVM layout pipeline must match what
 * bpmn-js measures, so the pipeline produces label boxes that fit the text it renders.
 *
 * **Font contract:** exact for `font = "12px Arial, sans-serif"` — the font and size
 * bpmn-js uses for external labels (via diagram-js Text). If the bpmn-js default label
 * font or size changes, regenerate the table by running the Playwright capture script
 * and replacing `src/main/resources/label-metrics.json` (and its copy in
 * `src/test/resources/`).
 *
 * **Provenance:** each entry is `canvas.getContext('2d').measureText(char).width`
 * at `font = "12px Arial, sans-serif"` captured in real Chromium via Playwright.
 * Single source of truth: `src/main/resources/label-metrics.json`.
 * The TS module `web/test/label-metrics.data.ts` is a compiled copy of that JSON.
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

    private val ADVANCE: DoubleArray = loadAdvanceTable()

    private const val ADVANCE_BASE = 32
    private const val ADVANCE_MAX = 126

    private fun loadAdvanceTable(): DoubleArray {
        val stream = LabelMetrics::class.java.getResourceAsStream("/label-metrics.json")
            ?: error("label-metrics.json not found on classpath")
        val root = ObjectMapper().readTree(stream)
        val advances = root.get("advances")
        val table = DoubleArray(ADVANCE_MAX - ADVANCE_BASE + 1) { DEFAULT_ADVANCE }
        val fieldNames = advances.fieldNames()
        while (fieldNames.hasNext()) {
            val key = fieldNames.next()
            val cp = key.toIntOrNull() ?: continue
            if (cp in ADVANCE_BASE..ADVANCE_MAX) {
                table[cp - ADVANCE_BASE] = advances.get(key).doubleValue()
            }
        }
        return table
    }

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
