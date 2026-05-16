/*
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

package dev.groknull.bpmner.core

import org.w3c.dom.ls.LSInput
import org.w3c.dom.ls.LSResourceResolver
import java.io.InputStream
import java.io.Reader

class ClasspathResourceResolver : LSResourceResolver {
    override fun resolveResource(
        type: String?,
        namespaceURI: String?,
        publicId: String?,
        systemId: String?,
        baseURI: String?,
    ): LSInput? =
        systemId
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?.let { resourceName ->
                javaClass
                    .getResourceAsStream("/xsd/$resourceName")
                    ?.let { stream ->
                        ClasspathLSInput(publicId, systemId, stream)
                    }
            }
}

private class ClasspathLSInput(
    private var publicId: String?,
    private var systemId: String?,
    stream: InputStream,
) : LSInput {
    private var byteStream: InputStream? = stream

    override fun getPublicId() = publicId

    override fun setPublicId(publicId: String?) {
        this.publicId = publicId
    }

    override fun getSystemId() = systemId

    override fun setSystemId(systemId: String?) {
        this.systemId = systemId
    }

    override fun getByteStream() = byteStream

    override fun setByteStream(byteStream: InputStream?) {
        this.byteStream = byteStream
    }

    override fun getBaseURI() = null

    override fun setBaseURI(baseURI: String?) = Unit

    override fun getCharacterStream() = null

    override fun setCharacterStream(characterStream: Reader?) = Unit

    override fun getStringData() = null

    override fun setStringData(stringData: String?) = Unit

    override fun getCertifiedText() = false

    override fun setCertifiedText(certifiedText: Boolean) = Unit

    override fun getEncoding() = null

    override fun setEncoding(encoding: String?) = Unit
}
