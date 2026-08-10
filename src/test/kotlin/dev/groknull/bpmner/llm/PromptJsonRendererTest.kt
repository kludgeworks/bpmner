/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.llm

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.groknull.bpmner.bpmn.BoundaryEventKind
import dev.groknull.bpmner.bpmn.MultiInstanceMode
import dev.groknull.bpmner.contract.ActivityModifiers
import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractBoundaryEvent
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.ContractIteration
import dev.groknull.bpmner.contract.ContractLoop
import dev.groknull.bpmner.contract.ProcessContract
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A serialiser that drops a field passes every offline gate and fails only here: round-trip
 * equality through Jackson fails if a modifier is unreachable to the serialiser (a missing
 * `@JsonSubTypes` entry, a stray `@JsonIgnore`, an inclusion setting that eats a value).
 */
class PromptJsonRendererTest {
    private val objectMapper = jacksonObjectMapper()
    private val renderer = PromptJsonRenderer(objectMapper)

    private val contractWithAllModifiers = ProcessContract(
        id = "contract-round-trip",
        processName = "Round trip",
        summary = "Exercises every activity modifier.",
        trigger = "Something happens",
        activities = listOf(
            ContractActivity.Service(
                id = "act-loop",
                name = "Retry until success",
                modifiers = ActivityModifiers(
                    loop = ContractLoop(testBefore = false, loopCondition = "not yet successful", loopMaximum = 3),
                ),
            ),
            ContractActivity.Service(
                id = "act-iterate",
                name = "Process each item",
                modifiers = ActivityModifiers(
                    iteration = ContractIteration(
                        mode = MultiInstanceMode.PARALLEL,
                        collectionDescription = "each line item",
                    ),
                    boundaryEvents = listOf(
                        ContractBoundaryEvent(
                            kind = BoundaryEventKind.TIMER,
                            label = "60s timeout",
                            nextRef = "end-timed-out",
                            detail = "PT60S",
                        ),
                    ),
                ),
            ),
        ),
        endStates = listOf(
            ContractEndState(id = "end-timed-out", name = "Timed out"),
        ),
    )

    @Test
    fun `round-trips every activity modifier through Jackson`() {
        val json = renderer.render(contractWithAllModifiers)

        val roundTripped: ProcessContract = objectMapper.readValue(json)

        assertEquals(contractWithAllModifiers, roundTripped)
    }

    @Test
    fun `does not swallow a false testBefore`() {
        val json = renderer.render(contractWithAllModifiers)

        assertContains(json, "\"testBefore\":false")
    }

    @Test
    fun `NON_EMPTY omits unset modifiers rather than emitting null noise`() {
        val bareActivity = ProcessContract(
            id = "contract-bare",
            processName = "Bare",
            summary = "No modifiers set.",
            trigger = "Something happens",
            activities = listOf(ContractActivity.Service(id = "act-plain", name = "Plain task")),
            endStates = listOf(ContractEndState(id = "end-done", name = "Done")),
        )

        val json = renderer.render(bareActivity)

        assertFalse(json.contains("loop"))
        assertFalse(json.contains("iteration"))
        assertFalse(json.contains("boundaryEvents"))
    }
}
