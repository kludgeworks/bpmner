/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.validation

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.groknull.bpmner.domain.BpmnDefinition
import org.springframework.stereotype.Component
import java.security.MessageDigest

@Component
class BpmnFingerprintService {
    private val objectMapper: ObjectMapper = jacksonObjectMapper().findAndRegisterModules()

    fun serializeDefinition(definition: BpmnDefinition): String {
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(definition)
    }

    fun definitionFingerprint(definition: BpmnDefinition): String = textFingerprint(serializeDefinition(definition))

    fun diagnosticFingerprint(diagnostics: List<BpmnDiagnostic>): String = textFingerprint(diagnostics.fingerprintInput())

    /**
     * Fingerprint computed over **only the blocking (ERROR-severity)** diagnostics.
     *
     * Used by `BpmnRepairAgent.revalidateAndAdvance`'s "stuck blocking" guard. If we
     * compared the full fingerprint there, advisory warnings oscillating between
     * iterations would make the fingerprint change every round, masking a permanently-
     * stuck blocking error — the planner would burn through every remaining budget
     * action via `ReplanRequestedException` instead of TERMINATING.
     */
    fun blockingDiagnosticFingerprint(diagnostics: List<BpmnDiagnostic>): String {
        return textFingerprint(diagnostics.filter { it.isBlocking }.fingerprintInput())
    }

    fun promptFingerprint(prompt: String): String = textFingerprint(prompt)

    private fun List<BpmnDiagnostic>.fingerprintInput(): String = map { diagnostic ->
        listOf(
            diagnostic.source.name,
            diagnostic.rule.orEmpty(),
            diagnostic.severity.name,
            diagnostic.elementId.orEmpty(),
            diagnostic.objectRef.orEmpty(),
            diagnostic.repairScope?.name.orEmpty(),
            diagnostic.ownerRef.orEmpty(),
            diagnostic.message,
        ).joinToString("")
    }.sorted()
        .joinToString("")

    companion object {
        private const val FINGERPRINT_LENGTH = 12
    }

    private fun textFingerprint(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(FINGERPRINT_LENGTH)
    }
}
