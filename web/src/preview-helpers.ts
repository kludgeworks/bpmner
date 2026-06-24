/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

/**
 * Parses the BPMN XML from the JSON-encoded textContent of the embedded
 * script data holder. Returns an empty string if textContent is null/empty.
 */
export function parsePreviewXml(textContent: string | null): string {
	return JSON.parse(textContent ?? '""')
}

/**
 * Formats an unknown thrown value as a human-readable error message.
 */
export function formatError(error: unknown): string {
	return error instanceof Error ? error.message : String(error)
}
