/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

/**
 * Fetches `version.json` (baked from MODULE.bazel's release version at build
 * time — see web/BUILD.bazel's `version_json` target) and writes it into the
 * given footer element. Never throws — a missing or malformed response leaves
 * the footer empty rather than breaking studio boot.
 */
export async function populateVersionFooter(
	el: HTMLElement,
	fetchImpl: typeof fetch = fetch,
): Promise<void> {
	try {
		const res = await fetchImpl("version.json")
		if (!res.ok) return
		const data = (await res.json()) as { version?: string }
		if (data.version) el.textContent = `v${data.version}`
	} catch {
		// Network/parse failure — leave the footer empty.
	}
}
