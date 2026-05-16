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

package dev.groknull.bpmner.config

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import java.io.IOException
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
            parseTextModels(URI.create(catalogUrl).toURL().readText(Charsets.UTF_8))
        } catch (e: IOException) {
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
        } catch (e: JsonProcessingException) {
            logger.warn("Could not parse GitHub Models catalog response: {}", e.message)
            emptyList()
        }
}
