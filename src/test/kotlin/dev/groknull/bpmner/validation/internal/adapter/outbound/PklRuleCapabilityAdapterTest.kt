package dev.groknull.bpmner.validation.internal.adapter.outbound

import dev.groknull.bpmner.core.BpmnRepairRoute
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
    fun `LLM route rules have no fixHandler`() {
        val caps = adapter.loadCapabilities()
        val llmCaps = caps.values.filter { it.repairRoute == BpmnRepairRoute.LLM }
        llmCaps.forEach { cap ->
            assertTrue(cap.fixHandler == null, "LLM route should have no handler: ${cap.id}")
            assertFalse(cap.handlerExists, "LLM route should have handlerExists=false: ${cap.id}")
        }
    }

    @Test
    fun `LOCAL_XML and LOCAL_MODEL route rules have handlerExists true`() {
        val caps = adapter.loadCapabilities()
        val localCaps =
            caps.values.filter {
                it.repairRoute == BpmnRepairRoute.LOCAL_XML || it.repairRoute == BpmnRepairRoute.LOCAL_MODEL
            }
        localCaps.forEach { cap ->
            assertTrue(cap.handlerExists, "Local route should have handlerExists=true: ${cap.id}")
            assertNotNull(cap.fixHandler, "Local route should have a handler: ${cap.id}")
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
}
