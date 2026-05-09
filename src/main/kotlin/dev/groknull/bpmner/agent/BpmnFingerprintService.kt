package dev.groknull.bpmner.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.stereotype.Component
import java.security.MessageDigest

@Component
class BpmnFingerprintService {
    private val objectMapper: ObjectMapper = jacksonObjectMapper().findAndRegisterModules()

    fun serializeDefinition(definition: BpmnDefinition): String =
        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(definition)

    fun definitionFingerprint(definition: BpmnDefinition): String =
        textFingerprint(serializeDefinition(definition))

    fun diagnosticFingerprint(diagnostics: List<BpmnDiagnostic>): String =
        textFingerprint(
            diagnostics
                .map { diagnostic ->
                    listOf(
                        diagnostic.source.name,
                        diagnostic.rule.orEmpty(),
                        diagnostic.category.orEmpty(),
                        diagnostic.elementId.orEmpty(),
                        diagnostic.objectRef.orEmpty(),
                        diagnostic.repairScope?.name.orEmpty(),
                        diagnostic.ownerRef.orEmpty(),
                        diagnostic.message,
                    ).joinToString("\u001f")
                }
                .sorted()
                .joinToString("\u001e")
        )

    fun promptFingerprint(prompt: String): String = textFingerprint(prompt)

    private fun textFingerprint(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(12)
    }
}
