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

@Suppress("TooManyFunctions") // LSInput interface mandates 16 getters/setters
private abstract class MutableLSInput : LSInput {
    private var publicIdValue: String? = null
    private var systemIdValue: String? = null
    private var baseUriValue: String? = null
    private var byteStreamValue: InputStream? = null
    private var characterStreamValue: Reader? = null
    private var stringDataValue: String? = null
    private var certifiedTextValue: Boolean = false
    private var encodingValue: String? = null

    override fun getPublicId() = publicIdValue

    override fun getSystemId() = systemIdValue

    override fun getBaseURI() = baseUriValue

    override fun getByteStream() = byteStreamValue

    override fun getCharacterStream() = characterStreamValue

    override fun getStringData() = stringDataValue

    override fun getCertifiedText() = certifiedTextValue

    override fun getEncoding() = encodingValue

    override fun setPublicId(publicId: String?) {
        publicIdValue = publicId
    }

    override fun setSystemId(systemId: String?) {
        systemIdValue = systemId
    }

    override fun setBaseURI(baseURI: String?) {
        baseUriValue = baseURI
    }

    override fun setByteStream(byteStream: InputStream?) {
        byteStreamValue = byteStream
    }

    override fun setCharacterStream(characterStream: Reader?) {
        characterStreamValue = characterStream
    }

    override fun setStringData(stringData: String?) {
        stringDataValue = stringData
    }

    override fun setCertifiedText(certifiedText: Boolean) {
        certifiedTextValue = certifiedText
    }

    override fun setEncoding(encoding: String?) {
        encodingValue = encoding
    }
}

private class ClasspathLSInput(
    publicId: String?,
    systemId: String?,
    stream: InputStream,
) : MutableLSInput() {
    init {
        setPublicId(publicId)
        setSystemId(systemId)
        setByteStream(stream)
    }
}
