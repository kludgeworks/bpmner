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

package dev.groknull.bpmner.validation

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.groknull.bpmner.core.BpmnDefinition
import org.springframework.stereotype.Component
import java.security.MessageDigest

@Component
class BpmnFingerprintService {
    private val objectMapper: ObjectMapper = jacksonObjectMapper().findAndRegisterModules()

    fun serializeDefinition(definition: BpmnDefinition): String =
        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(definition)

    fun definitionFingerprint(definition: BpmnDefinition): String = textFingerprint(serializeDefinition(definition))

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
                    ).joinToString("")
                }.sorted()
                .joinToString(""),
        )

    fun promptFingerprint(prompt: String): String = textFingerprint(prompt)

    companion object {
        private const val FINGERPRINT_LENGTH = 12
    }

    private fun textFingerprint(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(FINGERPRINT_LENGTH)
    }
}
