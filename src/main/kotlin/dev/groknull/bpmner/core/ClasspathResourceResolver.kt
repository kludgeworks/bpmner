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
    ): LSInput? {
        val resourceName = systemId?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: return null
        val stream = javaClass.getResourceAsStream("/xsd/$resourceName") ?: return null
        return ClasspathLSInput(publicId, systemId, stream)
    }
}

private class ClasspathLSInput(
    private val publicId: String?,
    private val systemId: String?,
    private val stream: InputStream,
) : LSInput {
    override fun getPublicId() = publicId
    override fun getSystemId() = systemId
    override fun getBaseURI() = null
    override fun getByteStream() = stream
    override fun getCharacterStream(): Reader? = null
    override fun getStringData(): String? = null
    override fun getCertifiedText() = false
    override fun getEncoding() = null

    override fun setPublicId(publicId: String?) {}
    override fun setSystemId(systemId: String?) {}
    override fun setBaseURI(baseURI: String?) {}
    override fun setByteStream(byteStream: InputStream?) {}
    override fun setCharacterStream(characterStream: Reader?) {}
    override fun setStringData(stringData: String?) {}
    override fun setCertifiedText(certifiedText: Boolean) {}
    override fun setEncoding(encoding: String?) {}
}
