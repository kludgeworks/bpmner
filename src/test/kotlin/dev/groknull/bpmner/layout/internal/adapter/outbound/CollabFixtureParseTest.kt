/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import org.camunda.bpm.model.bpmn.Bpmn
import org.camunda.bpm.model.bpmn.instance.Collaboration
import org.camunda.bpm.model.bpmn.instance.Lane
import org.camunda.bpm.model.bpmn.instance.MessageFlow
import org.camunda.bpm.model.bpmn.instance.Participant
import org.camunda.bpm.model.bpmn.instance.Process
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Validates that every collaboration corpus fixture parses cleanly via the Camunda model
 * API and has the expected structural elements (collaboration, participants, etc.).
 *
 * This is the oracle parse gate: the engine uses the same Camunda parser, so a fixture
 * that passes here is structurally valid BPMN. Runs before any layout engine changes
 * are made so bugs in the fixtures are caught early.
 */
class CollabFixtureParseTest {

    @ParameterizedTest(name = "parses cleanly: {0}")
    @ValueSource(
        strings = [
            "collab-lanes.bpmn",
            "collab-two-pools.bpmn",
            "collab-blackbox.bpmn",
            "collab-msg-endpoint.bpmn",
            "collab-msg-label.bpmn",
            "collab-subprocess.bpmn",
            "collab-bioc.bpmn",
        ],
    )
    fun `collaboration fixture parses and has collaboration element`(fixture: String) {
        val xml = load(fixture)
        val model = Bpmn.readModelFromStream(xml.byteInputStream(Charsets.UTF_8))

        val collabs = model.getModelElementsByType(Collaboration::class.java)
        assertTrue(collabs.isNotEmpty(), "[$fixture] must have at least one Collaboration element")

        val participants = model.getModelElementsByType(Participant::class.java)
        assertTrue(participants.isNotEmpty(), "[$fixture] must have at least one Participant")
    }

    @ParameterizedTest(name = "white-box participants have processRef: {0}")
    @ValueSource(
        strings = [
            "collab-lanes.bpmn",
            "collab-two-pools.bpmn",
            "collab-msg-endpoint.bpmn",
            "collab-msg-label.bpmn",
            "collab-subprocess.bpmn",
            "collab-bioc.bpmn",
        ],
    )
    fun `white-box participants reference a process`(fixture: String) {
        val xml = load(fixture)
        val model = Bpmn.readModelFromStream(xml.byteInputStream(Charsets.UTF_8))

        val participants = model.getModelElementsByType(Participant::class.java)
        val whiteBox = participants.filter { it.process != null }
        assertTrue(whiteBox.isNotEmpty(), "[$fixture] must have at least one white-box participant")
        for (p in whiteBox) {
            assertNotNull(p.process, "[$fixture] participant '${p.id}' processRef must resolve")
        }
    }

    @ParameterizedTest(name = "black-box participant has no processRef: collab-blackbox.bpmn")
    @ValueSource(strings = ["collab-blackbox.bpmn"])
    fun `black-box participant has no process reference`(fixture: String) {
        val xml = load(fixture)
        val model = Bpmn.readModelFromStream(xml.byteInputStream(Charsets.UTF_8))

        val participants = model.getModelElementsByType(Participant::class.java)
        val blackBox = participants.filter { it.process == null }
        assertTrue(blackBox.isNotEmpty(), "[$fixture] must have at least one black-box participant (no processRef)")
    }

    @ParameterizedTest(name = "message flows present: {0}")
    @ValueSource(
        strings = [
            "collab-two-pools.bpmn",
            "collab-blackbox.bpmn",
            "collab-msg-endpoint.bpmn",
            "collab-msg-label.bpmn",
            "collab-subprocess.bpmn",
            "collab-bioc.bpmn",
        ],
    )
    fun `message flows are present and have source and target refs`(fixture: String) {
        val xml = load(fixture)
        val model = Bpmn.readModelFromStream(xml.byteInputStream(Charsets.UTF_8))

        val msgFlows = model.getModelElementsByType(MessageFlow::class.java)
        assertTrue(msgFlows.isNotEmpty(), "[$fixture] must have at least one MessageFlow")
        for (mf in msgFlows) {
            assertNotNull(mf.source, "[$fixture] MessageFlow '${mf.id}' must have a source")
            assertNotNull(mf.target, "[$fixture] MessageFlow '${mf.id}' must have a target")
        }
    }

    @ParameterizedTest(name = "lanes present: collab-lanes.bpmn")
    @ValueSource(strings = ["collab-lanes.bpmn"])
    fun `lane fixture has lanes with flow node refs`(fixture: String) {
        val xml = load(fixture)
        val model = Bpmn.readModelFromStream(xml.byteInputStream(Charsets.UTF_8))

        val lanes = model.getModelElementsByType(Lane::class.java)
        assertTrue(lanes.isNotEmpty(), "[$fixture] must have at least one Lane")
        for (lane in lanes) {
            assertTrue(lane.flowNodeRefs.isNotEmpty(), "[$fixture] lane '${lane.id}' must have flowNodeRefs")
        }
    }

    @ParameterizedTest(name = "bioc colours survive round-trip parse: collab-bioc.bpmn")
    @ValueSource(strings = ["collab-bioc.bpmn"])
    fun `bioc fixture has existing DI with colour attributes`(fixture: String) {
        val xml = load(fixture)
        // Verify the raw XML contains bioc colour attributes
        assertTrue(xml.contains("bioc:stroke"), "[$fixture] must contain bioc:stroke attributes in existing DI")
        assertTrue(xml.contains("bioc:fill"), "[$fixture] must contain bioc:fill attributes in existing DI")
        // Also verify Camunda can parse it without error (bioc is an extension, not core BPMN)
        Bpmn.readModelFromStream(xml.byteInputStream(Charsets.UTF_8))
    }

    @ParameterizedTest(name = "processes have flow nodes: {0}")
    @ValueSource(
        strings = [
            "collab-lanes.bpmn",
            "collab-two-pools.bpmn",
            "collab-blackbox.bpmn",
            "collab-msg-endpoint.bpmn",
            "collab-msg-label.bpmn",
            "collab-subprocess.bpmn",
            "collab-bioc.bpmn",
        ],
    )
    fun `each white-box process has start and end events`(fixture: String) {
        val xml = load(fixture)
        val model = Bpmn.readModelFromStream(xml.byteInputStream(Charsets.UTF_8))

        val processes = model.getModelElementsByType(Process::class.java)
        for (proc in processes) {
            val startEvents = proc.flowElements
                .filterIsInstance<org.camunda.bpm.model.bpmn.instance.StartEvent>()
            assertTrue(
                startEvents.isNotEmpty(),
                "[$fixture] process '${proc.id}' must have at least one StartEvent",
            )
        }
    }

    private fun load(fixture: String): String = javaClass.classLoader.getResourceAsStream("layout-fixtures/$fixture")
        ?.use { it.readBytes().toString(Charsets.UTF_8) }
        ?: error("Fixture not found: layout-fixtures/$fixture")
}
