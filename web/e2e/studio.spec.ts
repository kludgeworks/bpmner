/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import { expect, test } from "@playwright/test"
import {
	ABORTED_NO_STATUS,
	AWAITING_ANSWER,
	bpmnWithLabel,
	HAPPY_PATH,
	serveStudio,
	sse,
	stubBackend,
	submitDescription,
} from "./fixtures"

const VIEWS = ["#view-compose", "#view-run", "#view-result"] as const

/** Asserts the studio shows the named view and only that one. */
async function expectOnlyView(
	page: import("@playwright/test").Page,
	visible: (typeof VIEWS)[number],
): Promise<void> {
	for (const selector of VIEWS) {
		if (selector === visible) {
			await expect(page.locator(selector)).toBeVisible()
		} else {
			await expect(page.locator(selector)).toBeHidden()
		}
	}
}

test("shows one view at a time across a whole run", async ({ page }) => {
	// A class-based hide loses the cascade to any later same-specificity `display`, and every
	// view sets its own — which rendered all three at once, stacked down the page.
	await serveStudio(page)
	await stubBackend(page, HAPPY_PATH)
	await page.goto("/index.html")

	await expectOnlyView(page, "#view-compose")

	await submitDescription(page, "Make toast for breakfast")

	await expect(page.locator("#view-result")).toBeVisible()
	await expectOnlyView(page, "#view-result")
})

test("gives the telemetry console real size while the run is on screen", async ({
	page,
}) => {
	// The console shares the run view with the pipeline and must actually occupy its column:
	// jsdom reports no geometry, so nothing else here can tell a rendered panel from a
	// collapsed one.
	await serveStudio(page)
	await stubBackend(page, AWAITING_ANSWER)
	await page.goto("/index.html")

	await submitDescription(page, "Handle a customer complaint")
	await expectOnlyView(page, "#view-run")

	const console = page.locator(".run-telemetry")
	await expect(console).toBeVisible()

	const box = await console.boundingBox()
	expect(box, "the console must occupy the right-hand column").not.toBeNull()
	expect(box?.width ?? 0).toBeGreaterThan(300)
	expect(box?.height ?? 0).toBeGreaterThan(200)

	// It is the run's log, so it carries the run's own lines.
	await expect(page.locator("#telemetry-log li")).not.toHaveCount(0)
})

test("asks for clarification, then resumes on the answer", async ({ page }) => {
	await serveStudio(page)
	const { answers } = await stubBackend(page, AWAITING_ANSWER)
	await page.goto("/index.html")

	await submitDescription(page, "Handle a customer complaint")

	const clarify = page.locator("#clarify-region")
	await expect(clarify).toBeVisible()
	await expect(clarify).toContainText(
		"What final state should the process reach?",
	)
	await expect(clarify).toContainText("The order is completed")

	// The card is a region inside the run view, never a fourth view.
	await expectOnlyView(page, "#view-run")

	await clarify.getByRole("radio").first().check()
	await clarify.getByRole("button").last().click()

	await expect(clarify).toBeHidden()
	expect(answers).toHaveLength(1)
	expect(JSON.parse(answers[0]).answers).toContain("The order is completed")
})

test("does not badge a status-less failure as generated", async ({ page }) => {
	await serveStudio(page)
	await stubBackend(page, ABORTED_NO_STATUS)
	await page.goto("/index.html")

	await submitDescription(page, "A run that aborts before any phase completes")

	await expectOnlyView(page, "#view-result")
	await expect(page.locator("#result-badge")).not.toHaveText("generated")
	await expect(page.locator("#result-headline")).toHaveText(
		"BPMN generation stopped unexpectedly.",
	)
	await expect(page.locator("#empty-result")).toBeVisible()
})

test("recovers from a dropped connection without losing the run", async ({
	page,
}) => {
	await serveStudio(page)
	await stubBackend(page, HAPPY_PATH)

	// The first connection drops after two events, with no terminal seen; a short `retry:`
	// keeps the browser's own reconnect delay out of the test's way. The second connection
	// replays from seq 1, as the real server does.
	let attempt = 0
	await page.route("**/updates", async (route) => {
		attempt += 1
		if (attempt === 1) {
			await route.fulfill({
				contentType: "text/event-stream",
				body: `retry: 50\n\n${sse(HAPPY_PATH.slice(0, 2))}`,
			})
			return
		}
		await route.fulfill({
			contentType: "text/event-stream",
			body: sse(HAPPY_PATH),
		})
	})

	await page.goto("/index.html")
	await submitDescription(page, "Make toast for breakfast")

	await expect(page.locator("#stream-banner")).toBeVisible()
	await expect(page.locator("#stream-banner-text")).toHaveText(
		"Connection lost — reconnecting…",
	)

	await expect(page.locator("#stream-banner-text")).toContainText("Reconnected")
	await expect(page.locator("#view-result")).toBeVisible()
})

test("collapses the run summary panel without clipping the toggle button", async ({
	page,
}) => {
	await serveStudio(page)
	await stubBackend(page, HAPPY_PATH)
	await page.goto("/index.html")

	await submitDescription(page, "Make toast for breakfast")
	await expect(page.locator("#view-result")).toBeVisible()

	const summaryPanel = page.locator("#summary-panel")
	const summaryToggle = page.locator("#summary-toggle")

	// Initially open
	let panelBox = await summaryPanel.boundingBox()
	expect(panelBox?.width).toBeGreaterThan(300)
	await expect(summaryToggle).toBeVisible()

	// Click to collapse
	await summaryToggle.evaluate((el: HTMLElement) => el.click())

	// The panel's content sections should be hidden immediately
	await expect(page.locator(".summary-sections")).toBeHidden()
	await expect(page.locator(".summary-section").first()).toBeHidden()

	// Wait for the grid-template-columns CSS transition to finish
	await page.waitForTimeout(300)

	// Verify the panel collapsed completely to 0 width
	panelBox = await summaryPanel.boundingBox()
	expect(panelBox?.width).toBeLessThanOrEqual(1)

	// The toggle button must still be visible and un-clipped outside the 0-width panel
	await expect(summaryToggle).toBeVisible()
	let toggleBox = await summaryToggle.boundingBox()
	expect(toggleBox?.width).toBeGreaterThan(0)

	// Expand it back
	await summaryToggle.evaluate((el: HTMLElement) => el.click())

	// Sections immediately become visible again
	await expect(page.locator(".summary-sections")).toBeVisible()

	// Wait for CSS transition
	await page.waitForTimeout(300)

	// Width restores to full
	panelBox = await summaryPanel.boundingBox()
	expect(panelBox?.width).toBeGreaterThan(300)
})

test("treats an evicted run's stream as gone, not as reconnecting", async ({
	page,
}) => {
	await serveStudio(page)
	await stubBackend(page, HAPPY_PATH)
	await page.route("**/updates", async (route) => {
		await route.fulfill({ status: 404, body: "" })
	})

	await page.goto("/index.html")
	await submitDescription(page, "Make toast for breakfast")

	await expect(page.locator("#empty-title")).toHaveText(
		"This run is no longer available",
	)
	await expect(page.locator("#stream-banner")).toBeHidden()
})

test("clears a stale pause when another client already answered", async ({
	page,
}) => {
	await serveStudio(page)
	await stubBackend(page, AWAITING_ANSWER, { answersStatus: 409 })
	await page.goto("/index.html")

	await submitDescription(page, "Handle a customer complaint")
	const clarify = page.locator("#clarify-region")
	await clarify.getByRole("radio").first().check()
	await clarify.getByRole("button").last().click()

	await expect(clarify).toBeHidden()
	// Before the fix this stayed "Paused — needs you" forever, with the clock frozen, because
	// the 409 branch never cleared the local pause.
	await expect(page.locator("#now-running .tag")).toHaveText("Now running")
})

test("does not let a stale diagram fetch leak into a newly started run", async ({
	page,
}) => {
	await serveStudio(page)
	await stubBackend(page, HAPPY_PATH)

	let runCount = 0
	await page.route("**/api/bpmn/generations", async (route) => {
		runCount += 1
		const processId = runCount === 1 ? "first_run" : "second_run"
		await route.fulfill({
			status: 202,
			contentType: "application/json",
			body: JSON.stringify({
				processId,
				sseUrl: `api/bpmn/generations/${processId}/updates`,
			}),
		})
	})
	// The first run's diagram fetch is held open past when the second run starts; a fix that
	// still applies it after the fact would overwrite the second run's canvas.
	await page.route("**/bpmn", async (route) => {
		const forFirstRun = route.request().url().includes("first_run")
		if (forFirstRun) await new Promise((resolve) => setTimeout(resolve, 400))
		await route.fulfill({
			contentType: "application/xml",
			body: bpmnWithLabel(forFirstRun ? "Run one diagram" : "Run two diagram"),
		})
	})

	await page.goto("/index.html")
	await submitDescription(page, "First description")
	await expect(page.locator("#view-result")).toBeVisible()

	await page.click("#new-run-btn")
	await submitDescription(page, "Second description")
	await expect(page.locator("#view-result")).toBeVisible()

	// Long enough for the delayed first-run fetch to resolve and, without the fix, clobber the
	// second run's canvas.
	await page.waitForTimeout(600)

	await expect(page.locator("#canvas")).toContainText("Run two diagram")
	await expect(page.locator("#canvas")).not.toContainText("Run one diagram")
})
