/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import assert from "node:assert/strict"
import { describe, it } from "node:test"
import {
	buildClarifyFormHtml,
	type ClarifyState,
	renderClarifyForm,
} from "../src/clarify-form"

// ---------------------------------------------------------------------------
// buildClarifyFormHtml — pure function, no DOM needed
// ---------------------------------------------------------------------------

function makeState(overrides: Partial<ClarifyState> = {}): ClarifyState {
	return {
		prompt: "What starts the process?",
		round: 1,
		maxRounds: 3,
		submitting: false,
		...overrides,
	}
}

describe("buildClarifyFormHtml — HTML-escapes the prompt", () => {
	it("escapes a script injection in the prompt", () => {
		const html = buildClarifyFormHtml(
			makeState({ prompt: "<script>alert(1)</script>" }),
		)
		assert.ok(!html.includes("<script>"), "raw <script> must not appear")
		assert.ok(
			html.includes("&lt;script&gt;"),
			"angle brackets should be escaped",
		)
	})
})

describe("buildClarifyFormHtml — round indicator", () => {
	it("shows '1 of 3' for round=1, maxRounds=3", () => {
		const html = buildClarifyFormHtml(makeState({ round: 1, maxRounds: 3 }))
		assert.ok(html.includes("1 of 3"), `expected '1 of 3' in: ${html}`)
	})

	it("shows '2 of 3' for round=2, maxRounds=3", () => {
		const html = buildClarifyFormHtml(makeState({ round: 2, maxRounds: 3 }))
		assert.ok(html.includes("2 of 3"), `expected '2 of 3' in: ${html}`)
	})
})

describe("buildClarifyFormHtml — textarea and submit", () => {
	it("includes a textarea element", () => {
		const html = buildClarifyFormHtml(makeState())
		assert.ok(html.includes("<textarea"), "should include a <textarea>")
	})

	it("includes a submit button", () => {
		const html = buildClarifyFormHtml(makeState())
		assert.ok(
			html.includes('type="button"'),
			"should include a button with type=button",
		)
	})
})

describe("buildClarifyFormHtml — disabled when submitting", () => {
	it("disables both textarea and button when submitting=true", () => {
		const html = buildClarifyFormHtml(makeState({ submitting: true }))
		// Both the textarea and the button should carry the disabled attribute.
		const disabledCount = (html.match(/ disabled/g) ?? []).length
		assert.ok(
			disabledCount >= 2,
			`expected at least 2 'disabled' attributes; got ${disabledCount} in: ${html}`,
		)
	})

	it("does not disable when submitting=false", () => {
		const html = buildClarifyFormHtml(makeState({ submitting: false }))
		assert.ok(
			!html.includes(" disabled"),
			"should not have disabled attributes",
		)
	})
})

describe("buildClarifyFormHtml — inline error", () => {
	it("renders the error message when error is set", () => {
		const html = buildClarifyFormHtml(
			makeState({ error: "Couldn't submit — try again" }),
		)
		assert.ok(
			html.includes("Couldn't submit — try again"),
			"should include the error text",
		)
		assert.ok(
			html.includes("clarify-error"),
			"should include the error element class",
		)
	})

	it("omits error element when error is not set", () => {
		const html = buildClarifyFormHtml(makeState())
		assert.ok(!html.includes("clarify-error"), "no error element without error")
	})
})

// ---------------------------------------------------------------------------
// renderClarifyForm — minimal DOM mock (no jsdom needed)
// ---------------------------------------------------------------------------

function makeContainer() {
	const classes = new Set<string>(["hidden"])
	const listeners: Array<{
		selector: string
		type: string
		fn: EventListenerOrEventListenerObject
	}> = []

	const el = {
		innerHTML: "",
		classList: {
			add: (c: string) => classes.add(c),
			remove: (c: string) => classes.delete(c),
			has: (c: string) => classes.has(c),
		},
		querySelector: <T extends Element>(selector: string): T | null => {
			// Minimal querySelector that returns stub elements for test assertions.
			if (selector === ".clarify-submit") {
				return {
					addEventListener: (
						type: string,
						fn: EventListenerOrEventListenerObject,
					) => {
						listeners.push({ selector, type, fn })
					},
				} as unknown as T
			}
			if (selector === ".clarify-answer") {
				return { value: "  test answer  " } as unknown as T
			}
			return null
		},
	}
	return { el: el as unknown as HTMLElement, classes, listeners }
}

describe("renderClarifyForm — removes hidden class and sets innerHTML", () => {
	it("renders HTML and removes hidden", () => {
		const { el, classes } = makeContainer()
		renderClarifyForm(el, makeState(), () => {})

		assert.ok(!classes.has("hidden"), "should not be hidden after render")
		assert.ok(el.innerHTML.length > 0, "innerHTML should be non-empty")
	})
})

describe("renderClarifyForm — submit handler calls onSubmit with trimmed answer", () => {
	it("calls onSubmit with the trimmed textarea value on submit click", () => {
		const { el, listeners } = makeContainer()
		let submitted: string | null = null
		renderClarifyForm(el, makeState(), (answer) => {
			submitted = answer
		})

		// Find and invoke the button click handler
		const btnListener = listeners.find((l) => l.selector === ".clarify-submit")
		assert.ok(btnListener, "submit button should have a click listener")
		;(btnListener.fn as EventListener)(new Event("click"))

		assert.equal(
			submitted,
			"test answer",
			"onSubmit should receive trimmed answer",
		)
	})
})
