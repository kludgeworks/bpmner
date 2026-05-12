package dev.groknull.bpmner.validation.internal.adapter.outbound

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

data class RuleCategoryMetadata(
    val name: String,
    val shortCode: String,
)

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
    val autoFixable: Boolean,
    val fixStrategy: String,
    val fixMethod: String?,
    val replacementMap: Map<String, String>?,
    val hasTsImplementation: Boolean,
    val aliases: List<String>,
    val deprecated: Boolean,
    val replacedBy: List<String>,
    val deprecationReason: String?,
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
        val bareId = id.replace("^(bpmner|bpmnlint-plugin-bpmner)/".toRegex(), "")
        return catalog.rules.find { it.id == bareId || it.aliases.contains(bareId) }
    }
}
