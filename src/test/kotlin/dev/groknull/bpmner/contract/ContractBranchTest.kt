/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContractBranchTest {
    @Test
    fun `ConditionalBranch carries an id, label, condition, and optional nextRef`() {
        val branch =
            ConditionalBranch(
                id = "br-yes",
                label = "Eligible",
                condition = "score >= 750",
                nextRef = "act-fast",
            )
        assertEquals("CONDITIONAL", branch.kindName)
        assertEquals("score >= 750", branch.condition)
        assertEquals("act-fast", branch.nextRef)
    }

    @Test
    fun `DefaultBranch carries an id, label, and optional nextRef but no condition field`() {
        val branch =
            DefaultBranch(
                id = "br-fallback",
                label = "Manual review",
                nextRef = "act-manual",
            )
        assertEquals("DEFAULT", branch.kindName)
        assertEquals("Manual review", branch.label)
        assertEquals("act-manual", branch.nextRef)
    }

    @Test
    fun `UnconditionalBranch carries id, label, optional nextRef and no condition`() {
        val branch =
            UnconditionalBranch(
                id = "br-it",
                label = "IT prep",
                nextRef = "act-prep-it",
            )
        assertEquals("UNCONDITIONAL", branch.kindName)
        assertEquals("IT prep", branch.label)
    }

    @Test
    fun `nextRef defaults to null on all three subtypes`() {
        assertNull(ConditionalBranch(id = "a", label = "A", condition = "x").nextRef)
        assertNull(DefaultBranch(id = "b", label = "B").nextRef)
        assertNull(UnconditionalBranch(id = "c", label = "C").nextRef)
    }

    @Test
    fun `branch instances can be referenced through the sealed interface`() {
        val branches: List<ContractBranch> =
            listOf(
                ConditionalBranch(id = "a", label = "A", condition = "x"),
                DefaultBranch(id = "b", label = "B"),
                UnconditionalBranch(id = "c", label = "C"),
            )
        // Exhaustive matching is the type-system replacement for the old init-block invariants.
        val kinds = branches.map { it.kindName }
        assertTrue(kinds.containsAll(listOf("CONDITIONAL", "DEFAULT", "UNCONDITIONAL")))
    }
}
