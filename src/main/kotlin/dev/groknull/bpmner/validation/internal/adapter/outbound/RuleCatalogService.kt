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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.groknull.bpmner.validation.BpmnLintRuleIds
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

data class RuleCategoryMetadata(
    val name: String,
    val shortCode: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RepairMetadata(
    val kind: String = "LLM_MODEL_PATCH",
    val safety: String = "LLM_ONLY",
    val handler: String? = null,
    val replacementMap: Map<String, String>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BpmnRuleMetadata(
    val id: String,
    val name: String,
    val category: RuleCategoryMetadata,
    val slug: String,
    val intent: String,
    val forModellers: String,
    val forAI: String,
    val targetElements: List<String>,
    val severity: String,
    val errorMessages: Map<String, String>,
    val staticConfig: Any?,
    val hasTsImplementation: Boolean,
    val aliases: List<String>,
    val deprecated: Boolean,
    val replacedBy: List<String>,
    val deprecationReason: String?,
    val repair: RepairMetadata = RepairMetadata(),
    val layoutSensitive: Boolean = false,
)

data class RuleCatalog(
    val rules: List<BpmnRuleMetadata>,
)

@Component
class RuleCatalogService {
    private val logger = LoggerFactory.getLogger(RuleCatalogService::class.java)
    private val objectMapper = jacksonObjectMapper()

    val catalog: RuleCatalog by lazy {
        val resource = ClassPathResource("linter-rules.json")
        if (!resource.exists()) {
            logger.warn("linter-rules.json not found on classpath. Using empty catalog.")
            RuleCatalog(emptyList())
        } else {
            try {
                objectMapper.readValue<RuleCatalog>(resource.inputStream)
            } catch (e: JsonProcessingException) {
                logger.error("Failed to load linter-rules.json", e)
                RuleCatalog(emptyList())
            }
        }
    }

    fun getRule(id: String): BpmnRuleMetadata? {
        val bareId = BpmnLintRuleIds.bareRuleId(id)
        return catalog.rules.find { it.id == bareId || it.aliases.contains(bareId) }
    }
}
