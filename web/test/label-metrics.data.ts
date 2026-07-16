/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 *
 * Frozen glyph advance-width table for the bpmn-js default label font.
 *
 * Font contract: exact for `font = "12px Arial, sans-serif"` — the font and size
 * bpmn-js uses for external labels. If the bpmn-js default label font or size
 * changes, regenerate from src/test/resources/label-metrics.json using the
 * Playwright capture script documented in plans/557/ARCHITECTURE.md §AD-557-15.
 *
 * Provenance: each value is measureText(char).width at "12px Arial, sans-serif"
 * in real Chromium (Playwright). Single source of truth: src/test/resources/label-metrics.json.
 * This TS module is a compiled copy of that JSON for use in the jsdom harness.
 */

/** Advance width for any character outside the ASCII printable range (32..126). */
export const DEFAULT_ADVANCE = 6.673828125

/** Line height for bpmn-js external labels (measured single-line box height in real Chromium). */
export const LINE_HEIGHT = 14

/** Dense map: codepoint → advance width at "12px Arial, sans-serif" in Chromium. */
export const ADVANCES: Record<number, number> = {
	32: 3.333984375,
	33: 3.333984375,
	34: 4.259765625,
	35: 6.673828125,
	36: 6.673828125,
	37: 10.669921875,
	38: 8.00390625,
	39: 2.291015625,
	40: 3.99609375,
	41: 3.99609375,
	42: 4.669921875,
	43: 7.0078125,
	44: 3.333984375,
	45: 3.99609375,
	46: 3.333984375,
	47: 3.333984375,
	48: 6.673828125,
	49: 6.673828125,
	50: 6.673828125,
	51: 6.673828125,
	52: 6.673828125,
	53: 6.673828125,
	54: 6.673828125,
	55: 6.673828125,
	56: 6.673828125,
	57: 6.673828125,
	58: 3.333984375,
	59: 3.333984375,
	60: 7.0078125,
	61: 7.0078125,
	62: 7.0078125,
	63: 6.673828125,
	64: 12.181640625,
	65: 8.00390625,
	66: 8.00390625,
	67: 8.666015625,
	68: 8.666015625,
	69: 8.00390625,
	70: 7.330078125,
	71: 9.333984375,
	72: 8.666015625,
	73: 3.333984375,
	74: 6.0,
	75: 8.00390625,
	76: 6.673828125,
	77: 9.99609375,
	78: 8.666015625,
	79: 9.333984375,
	80: 8.00390625,
	81: 9.333984375,
	82: 8.666015625,
	83: 8.00390625,
	84: 7.330078125,
	85: 8.666015625,
	86: 8.00390625,
	87: 11.326171875,
	88: 8.00390625,
	89: 8.00390625,
	90: 7.330078125,
	91: 3.333984375,
	92: 3.333984375,
	93: 3.333984375,
	94: 5.630859375,
	95: 6.673828125,
	96: 3.99609375,
	97: 6.673828125,
	98: 6.673828125,
	99: 6.0,
	100: 6.673828125,
	101: 6.673828125,
	102: 3.333984375,
	103: 6.673828125,
	104: 6.673828125,
	105: 2.666015625,
	106: 2.666015625,
	107: 6.0,
	108: 2.666015625,
	109: 9.99609375,
	110: 6.673828125,
	111: 6.673828125,
	112: 6.673828125,
	113: 6.673828125,
	114: 3.99609375,
	115: 6.0,
	116: 3.333984375,
	117: 6.673828125,
	118: 6.0,
	119: 8.666015625,
	120: 6.0,
	121: 6.0,
	122: 6.0,
	123: 4.0078125,
	124: 3.1171875,
	125: 4.0078125,
	126: 7.0078125,
}

/**
 * Total advance width for text, reproducing measureText(text).width at
 * "12px Arial, sans-serif" in Chromium for ASCII text.
 *
 * Trailing whitespace is NOT stripped here — callers handle it as needed.
 */
export function textWidth(text: string): number {
	let w = 0
	for (let i = 0; i < text.length; i++) {
		w += ADVANCES[text.charCodeAt(i)] ?? DEFAULT_ADVANCE
	}
	return w
}
