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

package dev.groknull.bpmner.validation.internal.adapter.outbound

import dev.groknull.bpmner.core.ClasspathResourceResolver
import dev.groknull.bpmner.validation.BpmnXsdValidationPort
import dev.groknull.bpmner.validation.XsdValidationIssue
import org.jmolecules.architecture.hexagonal.SecondaryAdapter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.xml.sax.SAXException
import java.io.IOException
import java.io.StringReader
import java.util.regex.Pattern
import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory

@SecondaryAdapter
@Component
internal open class BpmnXsdValidator : BpmnXsdValidationPort {
    private val logger = LoggerFactory.getLogger(BpmnXsdValidator::class.java)

    override fun validateDetailed(bpmnXml: String): List<XsdValidationIssue> {
        logger.debug("Starting XSD validation. xmlLength={}", bpmnXml.length)
        return try {
            val schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
            schemaFactory.resourceResolver = ClasspathResourceResolver()
            val schemaUrl =
                javaClass.getResource("/xsd/BPMN20.xsd")
                    ?: return listOf(XsdValidationIssue("BPMN20.xsd not found on classpath"))
            val schema = schemaFactory.newSchema(schemaUrl)
            schema.newValidator().validate(StreamSource(StringReader(bpmnXml)))
            logger.debug("XSD validation passed")
            emptyList()
        } catch (e: SAXException) {
            logger.debug("XSD validation failed: {}", e.message)
            listOf(XsdValidationIssue(e.message ?: "Unknown XSD validation error", extractElementId(e.message)))
        } catch (e: IOException) {
            logger.warn("Unexpected XSD validation error: {}", e.message)
            listOf(XsdValidationIssue("XSD validation error: ${e.message}", extractElementId(e.message)))
        }
    }

    private fun extractElementId(message: String?): String? {
        if (message.isNullOrBlank()) return null
        val matcher = ELEMENT_ID_PATTERN.matcher(message)
        return if (matcher.find()) matcher.group() else null
    }

    companion object {
        private val ELEMENT_ID_PATTERN: Pattern = Pattern.compile("[A-Za-z][A-Za-z0-9]*_[A-Za-z0-9]+")
    }
}
