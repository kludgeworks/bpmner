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
				const JavaString = Java.type("java.lang.String") as JavaStringConstructor
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
				return (globalThis as any).Buffer.from(input, "base64").toString(
					"binary",
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
