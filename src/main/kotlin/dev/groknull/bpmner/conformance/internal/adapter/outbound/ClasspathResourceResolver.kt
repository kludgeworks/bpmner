/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.conformance.internal.adapter.outbound

import org.w3c.dom.ls.LSInput
import org.w3c.dom.ls.LSResourceResolver
import java.io.InputStream
import java.io.Reader

internal class ClasspathResourceResolver : LSResourceResolver {
    override fun resolveResource(
        type: String?,
        namespaceURI: String?,
        publicId: String?,
        systemId: String?,
        baseURI: String?,
    ): LSInput? = systemId
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
