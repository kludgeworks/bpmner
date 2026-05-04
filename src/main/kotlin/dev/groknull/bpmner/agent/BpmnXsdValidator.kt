package dev.groknull.bpmner.agent

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.xml.sax.SAXException
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory

@Component
class BpmnXsdValidator {

    private val logger = LoggerFactory.getLogger(BpmnXsdValidator::class.java)

    fun validate(bpmnXml: String): String? {
        logger.debug("Starting XSD validation. xmlLength={}", bpmnXml.length)
        return try {
            val schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
            schemaFactory.resourceResolver = ClasspathResourceResolver()
            val schemaUrl = javaClass.getResource("/xsd/BPMN20.xsd")
                ?: return "BPMN20.xsd not found on classpath"
            val schema = schemaFactory.newSchema(schemaUrl)
            schema.newValidator().validate(StreamSource(StringReader(bpmnXml)))
            logger.debug("XSD validation passed")
            null
        } catch (e: SAXException) {
            logger.debug("XSD validation failed: {}", e.message)
            e.message
        } catch (e: Exception) {
            logger.warn("Unexpected XSD validation error: {}", e.message)
            "XSD validation error: ${e.message}"
        }
    }
}
