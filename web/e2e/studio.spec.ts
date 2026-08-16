/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import { expect, test } from "@playwright/test"
import {
	AWAITING_ANSWER,
	HAPPY_PATH,
	serveStudio,
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
