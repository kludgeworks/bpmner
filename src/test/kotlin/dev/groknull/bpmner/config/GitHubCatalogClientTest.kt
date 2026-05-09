package dev.groknull.bpmner.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GitHubCatalogClientTest {

    @Test
    fun `parses text-output models from catalog JSON`() {
        val json = """
            [
              {"id": "openai/gpt-4o", "publisher": "OpenAI", "supported_output_modalities": ["text"]},
              {"id": "meta/llama-3.3-70b-instruct", "publisher": "Meta", "supported_output_modalities": ["text"]},
              {"id": "openai/text-embedding-3-small", "publisher": "OpenAI", "supported_output_modalities": ["embeddings"]}
            ]
        """.trimIndent()

        val result = GitHubCatalogClient.parseTextModels(json)

        assertEquals(2, result.size)
        assertEquals(GitHubCatalogEntry("openai/gpt-4o", "OpenAI"), result[0])
        assertEquals(GitHubCatalogEntry("meta/llama-3.3-70b-instruct", "Meta"), result[1])
    }

    @Test
    fun `excludes models with no text output modality`() {
        val json = """
            [
              {"id": "openai/text-embedding-3-large", "publisher": "OpenAI", "supported_output_modalities": ["embeddings"]}
            ]
        """.trimIndent()

        val result = GitHubCatalogClient.parseTextModels(json)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `returns empty list for malformed JSON`() {
        val result = GitHubCatalogClient.parseTextModels("not json at all")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `returns empty list for empty array`() {
        val result = GitHubCatalogClient.parseTextModels("[]")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `handles model with multiple output modalities`() {
        val json = """
            [
              {"id": "some/multimodal", "publisher": "Acme", "supported_output_modalities": ["text", "embeddings"]}
            ]
        """.trimIndent()

        val result = GitHubCatalogClient.parseTextModels(json)

        assertEquals(1, result.size)
        assertEquals("some/multimodal", result[0].id)
    }
}
