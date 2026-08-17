/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.llm

import com.embabel.common.ai.converters.FilteringJacksonOutputConverter
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest
@ActiveProfiles("deepseek")
class JacksonCompatibilitySemanticsTest {
    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `Boot mapper preserves Jackson 2 parsing defaults for Embabel structured output`() {
        assertFalse(objectMapper.isEnabled(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY))
        assertFalse(objectMapper.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS))
        assertFalse(objectMapper.isEnabled(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES))
        assertFalse(objectMapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES))

        assertEquals(1, objectMapper.readValue("{\"count\":1} {\"count\":2}", PrimitiveValue::class.java).count)
        assertEquals(0, objectMapper.readValue("{\"count\":null}", PrimitiveValue::class.java).count)
        assertEquals(1, objectMapper.readValue("{\"count\":1,\"unexpected\":true}", PrimitiveValue::class.java).count)
    }

    @Test
    fun `Embabel schema converter uses the Boot configured mapper`() {
        val schema = objectMapper.readTree(
            FilteringJacksonOutputConverter(
                clazz = SchemaValue::class.java,
                objectMapper = objectMapper,
                fieldFilter = { true },
            ).jsonSchema,
        )

        val required = schema["required"]
        assertTrue(required.any { it.asString() == "name" })
        assertFalse(required.any { it.asString() == "optional" })
    }

    private data class PrimitiveValue(val count: Int)

    private data class SchemaValue(
        val name: String,
        val optional: String? = null,
    )
}
