/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

/**
 * GraalVM Polyglot global for Java interop.
 */
declare var Java: {
	/**
	 * Returns a Java type given its class name.
	 */
	type(className: string): unknown
}
