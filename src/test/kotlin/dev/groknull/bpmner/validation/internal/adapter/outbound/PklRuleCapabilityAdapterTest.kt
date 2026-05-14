package dev.groknull.bpmner.validation.internal.adapter.outbound

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PklRuleCapabilityAdapterTest {
    private val adapter = PklRuleCapabilityAdapter(RuleCatalogService())

    @Test
    fun `loadCapabilities returns non-empty map`() {
        val caps = adapter.loadCapabilities()
        assertTrue(caps.isNotEmpty(), "Expected capabilities from Pkl catalog")
    }

    @Test
    fun `loadCapabilities includes rule by canonical id`() {
        val caps = adapter.loadCapabilities()
        val anyKnown = caps.keys.any { it.isNotBlank() }
        assertTrue(anyKnown)
    }

    @Test
    fun `LLM kind rules have no fixHandler`() {
        val caps = adapter.loadCapabilities()
        val llmCaps = caps.values.filter { it.kind.isLlm() }
        llmCaps.forEach { cap ->
            assertTrue(cap.fixHandler == null, "LLM kind should have no handler: ${cap.id}")
            assertFalse(cap.handlerExists, "LLM kind should have handlerExists=false: ${cap.id}")
        }
    }

    @Test
    fun `LOCAL_XML_FIX and LOCAL_MODEL_FIX rules have handlerExists true`() {
        val caps = adapter.loadCapabilities()
        val localCaps = caps.values.filter { it.kind.isLocal() }
        localCaps.forEach { cap ->
            assertTrue(cap.handlerExists, "Local kind should have handlerExists=true: ${cap.id}")
            assertNotNull(cap.fixHandler, "Local kind should have a handler: ${cap.id}")
        }
    }

    @Test
    fun `aliases map to the same capability as canonical id`() {
        val caps = adapter.loadCapabilities()
        val capWithAlias = caps.values.firstOrNull { caps.keys.count { k -> caps[k] == it } > 1 }
        if (capWithAlias != null) {
            val aliasEntry = caps.entries.first { it.value == capWithAlias && it.key != capWithAlias.id }
            assertTrue(caps[aliasEntry.key] === caps[capWithAlias.id])
        }
    }

    @Test
    fun `layoutSensitive flag is surfaced for no-overlapping-elements via both id and alias`() {
        val caps = adapter.loadCapabilities()
        val byId = caps["no-overlapping-elements"]
        val byAlias = caps["bpmnlint/no-overlapping-elements"]
        assertNotNull(byId, "no-overlapping-elements capability not loaded")
        assertNotNull(byAlias, "bpmnlint/no-overlapping-elements alias not loaded")
        assertTrue(byId!!.layoutSensitive, "no-overlapping-elements should be layoutSensitive=true")
        assertTrue(byAlias!!.layoutSensitive, "alias should resolve to the same layoutSensitive capability")
    }

    @Test
    fun `layoutSensitive defaults to false for BPMNER rules`() {
        val caps = adapter.loadCapabilities()
        val nonLayoutSensitive = caps.values.filter { !it.layoutSensitive }
        assertTrue(nonLayoutSensitive.isNotEmpty(), "Expected at least one non layout-sensitive capability")
        val layoutSensitive = caps.values.distinct().filter { it.layoutSensitive }
        assertEquals(1, layoutSensitive.size, "Only no-overlapping-elements should be layoutSensitive today")
    }
}
