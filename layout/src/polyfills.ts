// GraalJS runs this bundle without Node globals, so we inject only the
// minimal shims required by the bundled dependencies.
if (typeof (globalThis as any).atob === 'undefined') {
  (globalThis as any).atob = function (input: string) {
    try {
      const Base64 = Java.type('java.util.Base64');
      const JavaString = Java.type('java.lang.String');
      const StandardCharsets = Java.type('java.nio.charset.StandardCharsets');

      const decodedBytes = Base64.getDecoder().decode(input);
      return new JavaString(decodedBytes, StandardCharsets.ISO_8859_1).toString();
    } catch (_error) {
      // Node-based smoke tests run without Java interop.
      return Buffer.from(input, 'base64').toString('binary');
    }
  };
}

// `bpmn-moddle` and other BPMN tools expect a subset of Node Buffer API.
if (typeof (globalThis as any).Buffer === 'undefined') {
  (globalThis as any).Buffer = {
    from: function(data: unknown, encoding: string) {
      if (encoding === 'base64' && typeof data === 'string') {
        const decoded = (globalThis as any).atob(data);
        return {
          toString: (enc: string) => {
            if (enc !== 'binary') {
              throw new Error(`Unsupported Buffer#toString encoding in polyfill: ${enc}`);
            }

            return decoded;
          },
        };
      }

      throw new Error(`Unsupported Buffer.from call in polyfill: ${encoding}`);
    },
  };
}
