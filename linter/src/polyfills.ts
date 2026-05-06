// Polyfills for GraalJS compatibility using Java Interop
// This is much faster than JS-based implementations
if (typeof (globalThis as any).atob === 'undefined') {
    (globalThis as any).atob = function (input: string) {
        try {
            const Base64 = Java.type('java.util.Base64');
            const JavaString = Java.type('java.lang.String');
            const StandardCharsets = Java.type('java.nio.charset.StandardCharsets');
            
            const decodedBytes = Base64.getDecoder().decode(input);
            return new JavaString(decodedBytes, StandardCharsets.ISO_8859_1).toString();
        } catch (e) {
            // Fallback for environments where Java interop might be restricted during tests
            return Buffer.from(input, 'base64').toString('binary');
        }
    };
}

// Some libraries expect 'Buffer'
if (typeof (globalThis as any).Buffer === 'undefined') {
    (globalThis as any).Buffer = {
        from: function(data: any, encoding: string) {
            if (encoding === 'base64') {
                const decoded = (globalThis as any).atob(data);
                const bytes = new Uint8Array(decoded.length);
                for (let i = 0; i < decoded.length; i++) {
                    bytes[i] = decoded.charCodeAt(i);
                }
                return {
                    toString: (enc: string) => enc === 'binary' ? decoded : 'unsupported'
                };
            }
            throw new Error('Unsupported Buffer.from call in polyfill');
        }
    };
}

