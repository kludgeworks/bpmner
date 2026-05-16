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

package dev.groknull.bpmner.repair.internal.domain

import dev.groknull.bpmner.core.BpmnDefinition
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
        ): List<BpmnPatchOperation> = emptyList()
    }
}
