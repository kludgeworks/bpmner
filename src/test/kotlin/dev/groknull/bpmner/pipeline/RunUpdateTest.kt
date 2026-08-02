/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.pipeline

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties

class RunUpdateTest {
    @Test
    fun `Progress carries every declared field`() {
        val update = RunUpdate.Progress(
            seq = 1,
            phase = RunPhase.READINESS,
            artifactState = ArtifactState.NONE,
            summary = "Assessed input readiness (ready).",
            detail = mapOf("k" to "v"),
        )

        assertEquals(1L, update.seq)
        assertEquals(RunPhase.READINESS, update.phase)
        assertEquals(ArtifactState.NONE, update.artifactState)
        assertEquals("Assessed input readiness (ready).", update.summary)
        assertEquals(mapOf("k" to "v"), update.detail)
    }

    @Test
    fun `Terminal defaults phase to FINISHED and carries its outcome`() {
        val update = RunUpdate.Terminal(
            seq = 9,
            artifactState = ArtifactState.FINAL,
            summary = "BPMN generation complete.",
            outcome = RunOutcome.COMPLETED,
        )

        assertEquals(RunPhase.FINISHED, update.phase)
        assertEquals(RunOutcome.COMPLETED, update.outcome)
    }

    @Test
    fun `supersedes is true against a null current (first update)`() {
        val first = progress(seq = 1)
        assertTrue(first.supersedes(null))
    }

    @Test
    fun `a strictly higher seq supersedes a lower one`() {
        val earlier = progress(seq = 1)
        val later = progress(seq = 2)
        assertTrue(later.supersedes(earlier))
    }

    @Test
    fun `an equal or lower seq never supersedes — prevents a stale update clobbering state`() {
        val a = progress(seq = 5)
        val b = progress(seq = 5)
        val earlier = progress(seq = 4)

        assertFalse(a.supersedes(b), "equal seq must not supersede")
        assertFalse(earlier.supersedes(a), "lower seq must not supersede a later one")
    }

    @Test
    fun `ordering is independent of artifactState — seq alone is authoritative`() {
        // A DIAGNOSTIC update with a higher seq still supersedes an earlier FINAL-looking one;
        // artifactState informs what a consumer renders, not whether it is newer.
        val earlierFinal = progress(seq = 3, artifactState = ArtifactState.FINAL)
        val laterDiagnostic = progress(seq = 4, artifactState = ArtifactState.DIAGNOSTIC)
        assertTrue(laterDiagnostic.supersedes(earlierFinal))
    }

    // Compile-time guarantee: catches a future field addition that would leak an
    // Embabel/raw domain type.
    @Test
    fun `every RunUpdate property is a primitive, enum, or flat string map`() {
        val allowedSimpleNames = setOf("Long", "String", "RunPhase", "ArtifactState", "RunOutcome", "Map")
        for (kClass: KClass<*> in listOf(RunUpdate.Progress::class, RunUpdate.Terminal::class)) {
            for (prop in kClass.memberProperties) {
                val simpleName = prop.returnType.classifier
                    ?.let { (it as? KClass<*>)?.simpleName }
                assertTrue(
                    simpleName in allowedSimpleNames,
                    "${kClass.simpleName}.${prop.name} has disallowed type $simpleName — " +
                        "RunUpdate must never carry an Embabel or raw domain type",
                )
            }
        }
    }

    // Pins the JSON shape web/src/run-update.ts hand-mirrors. No Jackson customization exists
    // anywhere in src/main, so the auto-configured Spring mapper's behavior matches
    // jacksonObjectMapper()'s Kotlin+Jackson defaults.
    @Test
    fun `Terminal serializes to exactly the fields the client expects`() {
        val terminal: RunUpdate = RunUpdate.Terminal(
            seq = 9,
            artifactState = ArtifactState.FINAL,
            summary = "BPMN generation complete.",
            outcome = RunOutcome.COMPLETED,
            detail = mapOf("status" to "GENERATED"),
        )

        val json = MAPPER.readTree(MAPPER.writeValueAsString(terminal))
        assertEquals(
            setOf("seq", "phase", "artifactState", "summary", "detail", "outcome"),
            json.fieldNames().asSequence().toSet(),
        )
        assertEquals("FINISHED", json["phase"].asText())
        assertEquals("FINAL", json["artifactState"].asText())
        assertEquals("COMPLETED", json["outcome"].asText())
    }

    // Load-bearing: web/src/run-update.ts's isTerminal() is literally `outcome !== undefined`.
    // Default Jackson inclusion is ALWAYS, so if a future refactor hoists a nullable `outcome`
    // onto the sealed RunUpdate interface, Progress would start serializing `"outcome": null`
    // and every non-terminal update would read as terminal in the browser. This is the one
    // assertion that catches that before it ships.
    @Test
    fun `Progress serializes with no outcome key at all`() {
        val progress: RunUpdate = progress(seq = 1)

        val json = MAPPER.readTree(MAPPER.writeValueAsString(progress))
        assertFalse(json.has("outcome"), "a Progress update must never carry an outcome key")
        assertEquals(
            setOf("seq", "phase", "artifactState", "summary", "detail"),
            json.fieldNames().asSequence().toSet(),
        )
    }

    private fun progress(
        seq: Long,
        artifactState: ArtifactState = ArtifactState.NONE,
    ): RunUpdate = RunUpdate.Progress(
        seq = seq,
        phase = RunPhase.READINESS,
        artifactState = artifactState,
        summary = "s",
    )

    private companion object {
        private val MAPPER = jacksonObjectMapper()
    }
}
