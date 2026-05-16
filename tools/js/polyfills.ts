/**
 * Copyright (c) 2026 The Project Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

// GraalJS runs this bundle without Node globals, so we inject only the
// minimal shims required by the bundled dependencies.

type Base64Decoder = {
	decode(input: string): unknown
}

type JavaBase64 = {
	getDecoder(): Base64Decoder
}

type JavaStringConstructor = new (
	bytes: unknown,
	charset: unknown,
) => {
	toString(): string
}

type JavaCharsets = {
	ISO_8859_1: unknown
}

type BufferPolyfill = {
	from(
		data: unknown,
		encoding: string,
	): {
		toString(enc: string): string
	}
}

type GraalGlobal = {
	atob?: (input: string) => string
	Buffer?: BufferPolyfill
}

const graalGlobal = globalThis as unknown as GraalGlobal

/**
 * Installs GraalJS polyfills for atob and Buffer.
 */
export function installPolyfills() {
	if (typeof graalGlobal.atob === "undefined") {
		graalGlobal.atob = (input: string) => {
			try {
				const Base64 = Java.type("java.util.Base64") as JavaBase64
				const JavaString = Java.type(
					"java.lang.String",
				) as JavaStringConstructor
				const StandardCharsets = Java.type(
					"java.nio.charset.StandardCharsets",
				) as JavaCharsets

				const decodedBytes = Base64.getDecoder().decode(input)
				return new JavaString(
					decodedBytes,
					StandardCharsets.ISO_8859_1,
				).toString()
			} catch (_error) {
				// Node-based smoke tests run without Java interop.
				// In Node, global Buffer is available.
				return (
					graalGlobal.Buffer?.from(input, "base64").toString("binary") ?? ""
				)
			}
		}
	}

	// `bpmn-moddle` and other BPMN tools expect a subset of Node Buffer API.
	if (typeof graalGlobal.Buffer === "undefined") {
		graalGlobal.Buffer = {
			from: (data: unknown, encoding: string) => {
				if (encoding === "base64" && typeof data === "string") {
					const decoded = graalGlobal.atob?.(data)
					return {
						toString: (enc: string) => {
							if (enc !== "binary") {
								throw new Error(
									`Unsupported Buffer#toString encoding in polyfill: ${enc}`,
								)
							}

							return decoded ?? ""
						},
					}
				}

				throw new Error(`Unsupported Buffer.from call in polyfill: ${encoding}`)
			},
		}
	}
}
