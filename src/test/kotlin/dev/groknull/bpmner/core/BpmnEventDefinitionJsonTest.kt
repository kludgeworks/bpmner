/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.core

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BpmnEventDefinitionJsonTest {
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()

    @Test
    fun `start event serializes event definition with nested discriminator`() {
        val definition =
            BpmnDefinition(
                processId = "Process_events",
                processName = "Event starts",
                nodes =
                    listOf(
                        BpmnStartEvent(
                            id = "Start_timer",
                            name = "Every morning",
                            eventDefinition = BpmnTimerEventDefinition(BpmnTimerKind.CYCLE, "R/PT24H"),
                        ),
                        BpmnEndEvent("End_1", "Done"),
                    ),
                sequences = listOf(BpmnEdge("Flow_1", "Start_timer", "End_1")),
            )

        val json = objectMapper.writeValueAsString(definition)
        val roundTrip = objectMapper.readValue<BpmnDefinition>(json)

        assertTrue(json.contains("\"eventDefinition\":{\"type\":\"TIMER\""))
        assertEquals(definition, roundTrip)
    }
}
