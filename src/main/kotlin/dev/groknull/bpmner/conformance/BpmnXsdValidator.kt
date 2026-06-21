/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.conformance

import dev.groknull.bpmner.conformance.internal.adapter.outbound.ClasspathResourceResolver
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
