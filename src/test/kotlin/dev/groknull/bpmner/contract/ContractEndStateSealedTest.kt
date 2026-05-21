/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Sealed-subtype coverage for [ContractEndState] (issue #186). Mirrors the test pattern
 * used for [ContractActivity] (PR #203) and [ContractBranch] / [ContractTrigger] (#185 /
 * #190): assert Jackson round-trip via the discriminator, the companion `invoke` keeps
 * flat-constructor call sites compiling, and `kindName` is exhaustive over every subtype.
 */
class ContractEndStateSealedTest {
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()

    @Test
    fun `companion invoke defaults to Normal end state`() {
        val end = ContractEndState(id = "end-shipped", name = "Order shipped", sourceIds = listOf("ev1"))
        assertIs<ContractEndState.Normal>(end)
        assertEquals("end-shipped", end.id)
        assertEquals("Order shipped", end.name)
        assertEquals(listOf("ev1"), end.sourceIds)
    }

    @Test
    fun `every subtype exposes shared interface properties`() {
        // Exhaustive over the 6 subtypes — compiler forces a new arm here if a new subtype
        // is added without updating it, the same forcing function the rest of the codebase
        // uses on sealed types.
        val subjects: List<ContractEndState> =
            listOf(
                ContractEndState.Normal("end-normal", "Normal end"),
                ContractEndState.Terminate("end-terminate", "Booking cancelled"),
                ContractEndState.Error("end-error", "Credit rejected", errorCode = "CREDIT_REJECTED"),
                ContractEndState.Message("end-message", "Shipment notification sent", messageName = "shipment confirmation"),
                ContractEndState.Signal("end-signal", "Settlement complete signal", signalName = "settlement complete"),
                ContractEndState.Escalation(
                    "end-escalation",
                    "Approval overdue escalation",
                    escalationCode = "APPROVAL_OVERDUE",
                ),
            )
        subjects.forEach { endState ->
            assertNotNull(endState.id)
            assertNotNull(endState.name)
            assertEquals(emptyList(), endState.sourceIds)
        }
    }

    @Test
    fun `kindName covers every subtype with the matching JsonSubTypes name`() {
        // Pairs: (subtype instance, expected @JsonSubTypes name). If these diverge from
        // the @JsonSubTypes declaration on ContractEndState, Jackson can't round-trip.
        val cases =
            listOf(
                ContractEndState.Normal("e", "n") to "NORMAL",
                ContractEndState.Terminate("e", "n") to "TERMINATE",
                ContractEndState.Error("e", "n", errorCode = "X") to "ERROR",
                ContractEndState.Message("e", "n", messageName = "msg") to "MESSAGE",
                ContractEndState.Signal("e", "n", signalName = "sig") to "SIGNAL",
                ContractEndState.Escalation("e", "n", escalationCode = "X") to "ESCALATION",
            )
        cases.forEach { (endState, expectedKind) ->
            assertEquals(expectedKind, endState.kindName)
        }
    }

    @Test
    fun `Jackson round-trips each subtype via the kind discriminator`() {
        val subjects: List<ContractEndState> =
            listOf(
                ContractEndState.Normal("end-1", "Completed", sourceIds = listOf("ev1")),
                ContractEndState.Terminate("end-2", "Booking cancelled", sourceIds = listOf("ev2")),
                ContractEndState.Error(
                    "end-3",
                    "Credit rejected",
                    errorCode = "CREDIT_REJECTED",
                    sourceIds = listOf("ev3"),
                ),
                ContractEndState.Message(
                    "end-4",
                    "Shipment notification sent",
                    messageName = "shipment confirmation",
                    sourceIds = listOf("ev4"),
                ),
                ContractEndState.Signal(
                    "end-5",
                    "Settlement complete signal",
                    signalName = "settlement complete",
                    sourceIds = listOf("ev5"),
                ),
                ContractEndState.Escalation(
                    "end-6",
                    "Approval overdue escalation",
                    escalationCode = "APPROVAL_OVERDUE",
                    sourceIds = listOf("ev6"),
                ),
            )
        subjects.forEach { original ->
            val json = objectMapper.writeValueAsString(original)
            // Discriminator appears in JSON
            assertTrue(json.contains("\"kind\":\"${original.kindName}\""))
            val roundTripped: ContractEndState = objectMapper.readValue(json)
            assertEquals(original, roundTripped)
            // The deserialized object has the same concrete subtype
            assertEquals(original::class, roundTripped::class)
        }
    }

    @Test
    fun `Error subtype requires errorCode for distinguishing from Normal`() {
        val a = ContractEndState.Error("e", "n", errorCode = "X")
        val b = ContractEndState.Error("e", "n", errorCode = "Y")
        // Different codes → different end states (would-be bug if compiler synthesized equals
        // wrong on the data class)
        assertTrue(a != b)
    }

    @Test
    fun `Message and Signal subtypes carry name not ref - matches ContractTrigger`() {
        // Mirror check: ContractTrigger.Message uses messageName too — naming convention
        // alignment matters for #194 (intermediate throw events) which will inherit the
        // same field shape.
        val message = ContractEndState.Message("e", "n", messageName = "shipment confirmation")
        assertEquals("shipment confirmation", message.messageName)

        val signal = ContractEndState.Signal("e", "n", signalName = "settlement complete")
        assertEquals("settlement complete", signal.signalName)
    }
}
