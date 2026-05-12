/**
 * GraalVM Polyglot global for Java interop.
 */
declare var Java: {
	/**
	 * Returns a Java type given its class name.
	 */
	type(className: string): unknown
}
