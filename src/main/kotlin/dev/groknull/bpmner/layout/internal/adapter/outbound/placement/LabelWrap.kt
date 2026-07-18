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
 * Entry point: [layout] returns line count and max fitted-line width for a label string
 * in a box of given width. Uses [LabelMetrics.width] in place of `measureText` so the
 * JVM estimator and bpmn-js's renderer produce identical line counts for every fixture label.
 *
 * bpmner padding is 0, so `maxWidth = box.width` (90 for nodes, 120 for edges).
 */
internal object LabelWrap {

    private const val SOFT_BREAK = '\u00AD'

    /**
     * Returns the line count and max fitted-line width for [text] in a box of [maxWidth].
     *
     * Splits on explicit newlines first, then applies the `layoutNext` fit loop to each
     * segment until all input is consumed. Blank text returns zero lines.
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
     * Removes the first entry from [lines], fits it to [maxWidth] (possibly shortening
     * it and pushing the remainder back onto [lines]), and returns (width, fittedText).
     *
     * The fit condition — `width < round(maxWidth)` or `line.length < 2` — is exact from
     * diagram-js so single-character and exactly-fitting lines are handled identically.
     */
    private fun layoutNext(lines: MutableList<String>, maxWidth: Double): Pair<Double, String> {
        val originalLine = lines.removeAt(0)
        var fitLine = originalLine

        while (true) {
            // strip trailing whitespace before measuring (matches diagram-js getTextBBox)
            val measurable = fitLine.trimEnd()
            val w = if (measurable.isEmpty()) 0.0 else LabelMetrics.width(measurable)

            if (fitsInBox(fitLine, w, maxWidth)) {
                return fit(lines, fitLine, originalLine, w)
            }

            fitLine = shortenLine(fitLine, w, maxWidth)
        }
    }

    /** Returns true when [line] fits: empty, single-space, shorter than 2 chars, or narrower than [maxWidth]. */
    private fun fitsInBox(line: String, width: Double, maxWidth: Double): Boolean =
        line == " " || line.isEmpty() || width < round(maxWidth) || line.length < 2

    /**
     * Pushes any [originalLine] remainder (the part after [fitLine]) back onto [lines] and
     * returns (width, fitLine). The remainder is trimmed before re-insertion.
     */
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
     * Shortens [line] to fit within [maxWidth]: tries semantic break points first,
     * then force-cuts at the proportional length estimate if no break is found.
     *
     * The length estimate `line.length × (maxWidth / width)` is exact from diagram-js
     * `shortenLine`; force-cut takes `max(round(length - 1), 1)`.
     */
    private fun shortenLine(line: String, width: Double, maxWidth: Double): String {
        val length = max(line.length * (maxWidth / width), 1.0)
        val shortened = semanticShorten(line, length)
        if (shortened.isNotEmpty()) return shortened
        // force-cut: slice to max(round(length-1), 1)
        return line.substring(0, max(round(length - 1).toInt(), 1))
    }

    /**
     * Shortens [line] at the last semantic break point (space, hyphen, soft-break) that fits
     * within [maxLength] character-count positions.
     *
     * Splits on `/(\s|-|\u00AD)/g` keeping delimiters as tokens, accumulates parts while
     * `part.length + accumulated < maxLength`, pops the preceding word when the break token
     * is a hyphen or soft-break, and converts a trailing soft-break to a literal hyphen.
     *
     * Returns an empty string when no semantic break is possible; the caller force-cuts instead.
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
     * Splits [text] on `(\s|-|\u00AD)` keeping the delimiters as tokens, matching the
     * capturing-group behaviour of JS `String.split(/(\s|-|\u00AD)/g)`.
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
