/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import assert from "node:assert/strict"
import { describe, it } from "node:test"
import { populateVersionFooter } from "../src/version-footer"

function makeFooter(): HTMLElement {
	let text = ""
	return {
		set textContent(value: string) {
			text = value
		},
		get textContent() {
			return text
		},
	} as unknown as HTMLElement
}

function fakeFetch(response: unknown, ok = true): typeof fetch {
	return (async () =>
		({
			ok,
			json: async () => response,
		}) as Response) as typeof fetch
}

describe("populateVersionFooter", () => {
	it("writes the fetched version into the element", async () => {
		const el = makeFooter()
		await populateVersionFooter(el, fakeFetch({ version: "0.3.2" }))
		assert.equal(el.textContent, "v0.3.2")
	})

	it("leaves the element untouched when the response is not ok", async () => {
		const el = makeFooter()
		await populateVersionFooter(el, fakeFetch({ version: "0.3.2" }, false))
		assert.equal(el.textContent, "")
	})

	it("leaves the element untouched when version is missing", async () => {
		const el = makeFooter()
		await populateVersionFooter(el, fakeFetch({}))
		assert.equal(el.textContent, "")
	})

	it("swallows fetch rejection without throwing", async () => {
		const el = makeFooter()
		const rejecting: typeof fetch = (async () => {
			throw new Error("network down")
		}) as typeof fetch
		await assert.doesNotReject(() => populateVersionFooter(el, rejecting))
		assert.equal(el.textContent, "")
	})
})
