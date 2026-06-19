/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain

import dev.groknull.bpmner.domain.BpmnDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BpmnLocalModelFixHandlerRegistryTest {
    @Test
    fun `lookup returns null for unknown handler name`() {
        val registry = BpmnLocalModelFixHandlerRegistry(listOf(StubHandler("alpha")))
        assertNull(registry.lookup("missing"))
    }

    @Test
    fun `lookup returns registered handler by name`() {
        val handler = StubHandler("alpha")
        val registry = BpmnLocalModelFixHandlerRegistry(listOf(handler))
        assertEquals(handler, registry.lookup("alpha"))
    }

    @Test
    fun `registeredNames lists all handler names`() {
        val registry = BpmnLocalModelFixHandlerRegistry(listOf(StubHandler("alpha"), StubHandler("beta")))
        assertEquals(setOf("alpha", "beta"), registry.registeredNames())
    }

    @Test
    fun `duplicate handler names fail construction`() {
        val error =
            assertFailsWith<IllegalStateException> {
                BpmnLocalModelFixHandlerRegistry(listOf(StubHandler("alpha"), StubHandler("alpha")))
            }
        assertTrue(error.message!!.contains("alpha"))
    }

    private class StubHandler(
        override val handlerName: String,
    ) : BpmnLocalModelFixHandler {
        override fun buildPatch(
            definition: BpmnDefinition,
            elementId: String,
            config: HandlerConfig,
        ): List<BpmnPatchOperation> = emptyList()
    }
}
