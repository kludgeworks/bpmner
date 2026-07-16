/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

// ported verbatim from diagram-js@15.14.0 Text.js
package dev.groknull.bpmner.layout.internal.adapter.outbound.placement

import kotlin.math.max
import kotlin.math.round

/**
 * Line-break algorithm ported verbatim from diagram-js@15.14.0 `lib/util/Text.js`.
 *
 * Reproduces `layoutText`'s outer loop using [LabelMetrics.width] in place of
 * `measureText`, so the JVM estimator and the bpmn-js oracle produce identical
 * line counts for every corpus label (AD-557-15 estimator⇄oracle agreement gate).
 *
 * Only the width-measurement and line-count parts are ported — not SVG `<tspan>`
 * emission, alignment, or y-offset magic (those are render-time, owned by bpmn-js).
 *
 * bpmner padding is 0, so `maxWidth = box.width` (90 for nodes, 120 for edges).
 */
internal object LabelWrap {

    private const val SOFT_BREAK = '\u00AD'

    /**
     * Returns the number of rendered lines for [text] in a box of [maxWidth].
     *
     * Matches diagram-js `layoutText` (with padding=0): explicit-newline split,
     * then `layoutNext` loop per line until all input is consumed.
     *
     * Also returns the maximum fitted-line width for use in `estimateLabelDimensions`.
     */
    internal fun layout(text: String, maxWidth: Double): LayoutResult {
        if (text.isBlank()) return LayoutResult(lineCount = 0, maxLineWidth = 0.0)

        // diagram-js: text.split(/\u00AD?\r?\n/) → initial list of lines
        val lines = text.split(Regex("\\u00AD?\\r?\\n")).toMutableList()
        var lineCount = 0
        var maxLineWidth = 0.0

        while (lines.isNotEmpty()) {
            val (w, _) = layoutNext(lines, maxWidth)
            lineCount++
            if (w > maxLineWidth) maxLineWidth = w
        }

        return LayoutResult(lineCount, maxLineWidth)
    }

    /** Convenience for callers that only need the line count. */
    internal fun lineCount(text: String, maxWidth: Double): Int = layout(text, maxWidth).lineCount

    /**
     * Lays out the next line: removes the first element from [lines], fits it to
     * [maxWidth], pushes any remainder back, and returns (width, lineText).
     *
     * Mirrors diagram-js `layoutNext`.
     */
    private fun layoutNext(lines: MutableList<String>, maxWidth: Double): Pair<Double, String> {
        val originalLine = lines.removeAt(0)
        var fitLine = originalLine

        while (true) {
            // strip trailing whitespace before measuring (matches diagram-js getTextBBox)
            val measurable = fitLine.trimEnd()
            val w = if (measurable.isEmpty()) 0.0 else LabelMetrics.width(measurable)

            // diagram-js fit condition: fits if width < round(maxWidth) or line is too short to shorten
            if (fitsInBox(fitLine, w, maxWidth)) {
                return fit(lines, fitLine, originalLine, w)
            }

            fitLine = shortenLine(fitLine, w, maxWidth)
        }
    }

    /** Returns true when diagram-js's layoutNext fit condition is satisfied. */
    private fun fitsInBox(line: String, width: Double, maxWidth: Double): Boolean =
        line == " " || line.isEmpty() || width < round(maxWidth) || line.length < 2

    /** Mirrors diagram-js `fit`: pushes any remainder back and returns (width, text). */
    private fun fit(
        lines: MutableList<String>,
        fitLine: String,
        originalLine: String,
        width: Double,
    ): Pair<Double, String> {
        if (fitLine.length < originalLine.length) {
            val remainder = originalLine.substring(fitLine.length).trim()
            if (remainder.isNotEmpty()) lines.add(0, remainder)
        }
        return width to fitLine
    }

    /**
     * Shortens [line] to fit within [maxWidth], first via semantic break points, then
     * via force-cut if no semantic break is found.
     *
     * Mirrors diagram-js `shortenLine`.
     */
    private fun shortenLine(line: String, width: Double, maxWidth: Double): String {
        val length = max(line.length * (maxWidth / width), 1.0)
        val shortened = semanticShorten(line, length)
        if (shortened.isNotEmpty()) return shortened
        // force-cut: slice to max(round(length-1), 1)
        return line.substring(0, max(round(length - 1).toInt(), 1))
    }

    /**
     * Shortens [line] at semantic break points (spaces, hyphens, soft-breaks).
     *
     * Mirrors diagram-js `semanticShorten`: splits on `/(\s|-|\u00AD)/g` (delimiters
     * kept as tokens), accumulates parts while `part.length + length < maxLength`,
     * pops the preceding part when the breaking token is `-` or soft-break, and
     * translates a trailing soft-break to a literal hyphen.
     *
     * Returns an empty string when no break is possible (caller falls back to force-cut).
     */
    private fun semanticShorten(line: String, maxLength: Double): String {
        val tokens = splitKeepingDelimiters(line)
        if (tokens.size <= 1) return ""

        val shortenedParts = mutableListOf<String>()
        var length = 0.0

        for (part in tokens) {
            if (part.length + length < maxLength) {
                shortenedParts.add(part)
                length += part.length
            } else {
                if (part == "-" || part == SOFT_BREAK.toString()) {
                    shortenedParts.removeLastOrNull()
                }
                break
            }
        }

        // translate trailing soft-break to hyphen
        val last = shortenedParts.lastOrNull()
        if (last != null && last == SOFT_BREAK.toString()) {
            shortenedParts[shortenedParts.size - 1] = "-"
        }

        return shortenedParts.joinToString("")
    }

    /**
     * Splits [text] on `(\s|-|\u00AD)` keeping the delimiters as tokens in the result,
     * matching the JS `String.split(/(\s|-|\u00AD)/g)` capturing-group behaviour.
     */
    private fun splitKeepingDelimiters(text: String): List<String> {
        val result = mutableListOf<String>()
        val delimRegex = Regex("[\\s\\-\u00AD]")
        var lastEnd = 0
        for (match in delimRegex.findAll(text)) {
            if (match.range.first > lastEnd) {
                result.add(text.substring(lastEnd, match.range.first))
            }
            result.add(match.value)
            lastEnd = match.range.last + 1
        }
        if (lastEnd < text.length) {
            result.add(text.substring(lastEnd))
        }
        return result
    }

    internal data class LayoutResult(val lineCount: Int, val maxLineWidth: Double)
}
