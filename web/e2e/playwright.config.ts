/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import { defineConfig, devices } from "@playwright/test"

/**
 * The studio's layout checks, which need a real browser.
 *
 * The jsdom suites cover logic; they compute no cascade and no layout, so a page that renders
 * every view at once, or a console collapsed to zero height, passes all of them. These specs
 * exist for exactly that gap and assert only what requires rendering.
 *
 * There is no web server: every request, the document included, is served from disk through
 * Playwright's own routing (see `serveStudio`). The origin below is never contacted.
 */
export default defineConfig({
	testDir: ".",
	fullyParallel: true,
	reporter: process.env.CI ? "line" : "list",
	use: {
		baseURL: "http://studio.test",
		...devices["Desktop Chrome"],
	},
})
