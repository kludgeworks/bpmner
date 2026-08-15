/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring

import dev.groknull.bpmner.authoring.internal.adapter.outbound.FlatBpmnEventDefinitionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Parity guard between the BPMN the emitter can produce and the vocabulary the contract can
 * authorise.
 *
 * For every BPMN event-definition kind the emitter ([FlatBpmnEventDefinitionKind]) can produce,
 * the contract must be able to authorise it via *some* element (a start trigger, an end state, or
 * an intermediate throw) — or the kind sits in [excluded] with a stated reason. `bpmn-profile.md`
 * is never an input to this comparison: it is hand-written prose that has claimed support for
 * constructs the contract could not authorise. This compares emitter code against contract code
 * directly, the same direction in which `rules.md` is generated from live bean metadata.
 *
 * Deliberately coarse — "authorised somewhere in the contract", not "authorised on every element
 * type the emitter allows it on". A kind can pass here while a single element type still lacks it,
 * so per-element exhaustiveness is guarded separately: [BoundaryEventKind]'s lives in
 * `FlatContractMapperTest`'s boundary-event round-trip. The two guards are complementary.
 */
internal class EmitterContractVocabularyParityTest {

    /**
     * Every [FlatBpmnEventDefinitionKind] the contract can authorise, via at least one element.
     * Kept in sync by hand — the cross-module `Kind.entries` exhaustiveness pattern 696-4
     * established, applied here across the emitter/contract boundary rather than within one enum.
     */
    private val authorised = setOf(
        FlatBpmnEventDefinitionKind.NONE, // ContractTrigger.None, ContractEndState.Normal
        FlatBpmnEventDefinitionKind.TIMER, // ContractTrigger.Timer, BoundaryEventKind.TIMER
        FlatBpmnEventDefinitionKind.MESSAGE, // trigger/end state/throw, BoundaryEventKind.MESSAGE
        FlatBpmnEventDefinitionKind.ERROR, // ContractEndState.Error, BoundaryEventKind.ERROR
        FlatBpmnEventDefinitionKind.TERMINATE, // ContractEndState.Terminate
        FlatBpmnEventDefinitionKind.SIGNAL, // trigger/end state/throw
        FlatBpmnEventDefinitionKind.ESCALATION, // end state/throw, BoundaryEventKind.ESCALATION
    )

    /**
     * Every [FlatBpmnEventDefinitionKind] the contract deliberately cannot authorise, with the
     * reason it stays excluded. Each reason cites the BPMN conformance tier the construct sits
     * in, never a sample count: a tier stays true, where a count expires the moment someone
     * writes the next fixture.
     */
    private val excluded = mapOf(
        FlatBpmnEventDefinitionKind.COMPENSATE to
            "below the Analytic conformance subclass (Common Executable only); degrades " +
            "gracefully — prose describing an undo extracts as an ordinary activity, never a " +
            "failed run.",
    )

    @Test
    fun `every emitter event-definition kind is authorised or explicitly excluded`() {
        val entries = FlatBpmnEventDefinitionKind.entries.toSet()
        val covered = authorised + excluded.keys
        assertEquals(
            entries,
            covered,
            "FlatBpmnEventDefinitionKind has a new entry with no disposition: ${entries - covered} — " +
                "add it to `authorised` (name the contract element that authorises it) or " +
                "`excluded` (name the conformance-tier reason it does not).",
        )
        assertTrue(
            (authorised intersect excluded.keys).isEmpty(),
            "kind(s) in both authorised and excluded: ${authorised intersect excluded.keys}",
        )
    }
}
