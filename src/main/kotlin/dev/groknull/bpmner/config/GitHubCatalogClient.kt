@file:Suppress(
    "CyclomaticComplexMethod",
    "ForbiddenComment",
    "LongMethod",
    "LongParameterList",
    "MagicNumber",
    "MaxLineLength",
    "NestedBlockDepth",
    "ReturnCount",
    "SpreadOperator",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
    "UnusedParameter",
    "UnusedPrivateProperty",
)

package dev.groknull.bpmner.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import java.net.URI

data class GitHubCatalogEntry(
    val id: String,
    val publisher: String,
)

object GitHubCatalogClient {
    private val logger = LoggerFactory.getLogger(GitHubCatalogClient::class.java)
    private val mapper = ObjectMapper().registerKotlinModule()

    fun fetchTextModels(catalogUrl: String = "https://models.github.ai/catalog/models"): List<GitHubCatalogEntry> =
        try {
            parseTextModels(URI(catalogUrl).toURL().readText(Charsets.UTF_8))
        } catch (e: Exception) {
            logger.warn("Could not fetch GitHub Models catalog from {}: {}", catalogUrl, e.message)
            emptyList()
        }

    internal fun parseTextModels(json: String): List<GitHubCatalogEntry> =
        try {
            mapper
                .readTree(json)
                .filter { node -> node.path("supported_output_modalities").any { it.asText() == "text" } }
                .map { node ->
                    GitHubCatalogEntry(
                        id = node.path("id").asText(),
                        publisher = node.path("publisher").asText(),
                    )
                }
        } catch (e: Exception) {
            logger.warn("Could not parse GitHub Models catalog response: {}", e.message)
            emptyList()
        }
}
