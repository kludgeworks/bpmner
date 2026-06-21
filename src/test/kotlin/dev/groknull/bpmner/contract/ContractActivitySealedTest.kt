/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.groknull.bpmner.bpmn.MultiInstanceMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Sealed-subtype coverage for [ContractActivity]. The LLM-facing schema lives on the flat DTO
 * (`FlatContractActivity`); this sealed hierarchy is the internal domain model, so it carries no
 * Jackson/Jakarta schema annotations. These tests pin the behaviour that survives that: Jackson
 * round-trip via the `kind` discriminator, the [ActivityModifiers] cross-cutting object, the
 * polymorphic [iteration] / [loop] / [dataInputIds] accessors, and `kindName` exhaustiveness.
 */
class ContractActivitySealedTest {
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()

    @Test
    fun `companion invoke defaults to a Service activity`() {
        val activity = ContractActivity(id = "act-1", name = "Charge card", sourceIds = listOf("ev1"))
        assertIs<ContractActivity.Service>(activity)
        assertEquals("act-1", activity.id)
        assertEquals("Charge card", activity.name)
        assertEquals(listOf("ev1"), activity.sourceIds)
        assertEquals(ActivityModifiers(), activity.modifiers)
    }

    @Test
    fun `kindName covers every subtype with the matching JsonSubTypes name`() {
        val cases =
            listOf(
                ContractActivity.Service("a", "n") to "SERVICE",
                ContractActivity.User("a", "n") to "USER",
                ContractActivity.Script("a", "n") to "SCRIPT",
                ContractActivity.BusinessRule("a", "n", decisionName = "credit policy") to "BUSINESS_RULE",
                ContractActivity.Send("a", "n", messageName = "decline notice") to "SEND",
                ContractActivity.Receive("a", "n", messageName = "ack") to "RECEIVE",
                ContractActivity.Manual("a", "n") to "MANUAL",
                ContractActivity.SubProcess("a", "n", containedActivityIds = listOf("m1")) to "SUB_PROCESS",
            )
        cases.forEach { (activity, expectedKind) ->
            assertEquals(expectedKind, activity.kindName)
        }
    }

    @Test
    fun `Jackson round-trips each subtype via the kind discriminator`() {
        val subjects: List<ContractActivity> =
            listOf(
                ContractActivity.Service("act-1", "Charge card", sourceIds = listOf("ev1")),
                ContractActivity.User("act-2", "Review manuscript", sourceIds = listOf("ev2")),
                ContractActivity.Script("act-3", "Compute score", sourceIds = listOf("ev3")),
                ContractActivity.BusinessRule("act-4", "Assess credit", decisionName = "credit policy"),
                ContractActivity.Send("act-5", "Notify decline", messageName = "decline notification"),
                ContractActivity.Receive("act-6", "Await ack", messageName = "customer acknowledgement"),
                ContractActivity.Manual("act-7", "File paperwork"),
                ContractActivity.SubProcess("sub-1", "Assess claim", containedActivityIds = listOf("act-1", "act-2")),
            )
        subjects.forEach { original ->
            val json = objectMapper.writeValueAsString(original)
            assertEquals(true, json.contains("\"kind\":\"${original.kindName}\""))
            val roundTripped: ContractActivity = objectMapper.readValue(json)
            assertEquals(original, roundTripped)
            assertEquals(original::class, roundTripped::class)
        }
    }

    @Test
    fun `cross-cutting modifiers round-trip and are readable via the polymorphic accessors`() {
        val original =
            ContractActivity.Service(
                id = "act-mi",
                name = "Review each item",
                sourceIds = listOf("ev1"),
                modifiers =
                ActivityModifiers(
                    iteration = ContractIteration(
                        mode = MultiInstanceMode.PARALLEL,
                        collectionDescription = "each reviewer on the panel",
                    ),
                    loop = ContractLoop(loopCondition = "not yet approved"),
                    dataInputIds = listOf("art-order"),
                    dataOutputIds = listOf("art-decision"),
                ),
            )

        val roundTripped: ContractActivity = objectMapper.readValue(objectMapper.writeValueAsString(original))

        assertEquals(original, roundTripped)
        // Polymorphic extension accessors read through `modifiers`.
        assertEquals(MultiInstanceMode.PARALLEL, roundTripped.iteration?.mode)
        assertEquals("not yet approved", roundTripped.loop?.loopCondition)
        assertEquals(listOf("art-order"), roundTripped.dataInputIds)
        assertEquals(listOf("art-decision"), roundTripped.dataOutputIds)
    }

    @Test
    fun `withSourceIds preserves the concrete subtype and other fields`() {
        val original = ContractActivity.BusinessRule("act-4", "Assess credit", decisionName = "credit policy")
        val updated = original.withSourceIds(listOf("ev9"))
        assertIs<ContractActivity.BusinessRule>(updated)
        assertEquals(listOf("ev9"), updated.sourceIds)
        assertEquals("credit policy", updated.decisionName)
    }
}
