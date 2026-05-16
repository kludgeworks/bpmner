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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GitHubCatalogClientTest {
    @Test
    fun `parses text-output models from catalog JSON`() {
        val json =
            """
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
        val json =
            """
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
        val json =
            """
            [
              {"id": "some/multimodal", "publisher": "Acme", "supported_output_modalities": ["text", "embeddings"]}
            ]
            """.trimIndent()

        val result = GitHubCatalogClient.parseTextModels(json)

        assertEquals(1, result.size)
        assertEquals("some/multimodal", result[0].id)
    }
}
