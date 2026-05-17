/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

declare module "bpmnlint-utils" {
	export function is(element: unknown, type: string): boolean
	export function isAny(element: unknown, types: string[]): boolean
}
