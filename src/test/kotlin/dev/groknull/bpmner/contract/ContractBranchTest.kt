/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContractBranchTest {
    @Test
    fun `ConditionalBranch carries an id, label, and condition`() {
        val branch =
            ConditionalBranch(
                id = "br-yes",
                label = "Eligible",
                condition = "score >= 750",
            )
        assertEquals("CONDITIONAL", branch.kindName)
        assertEquals("score >= 750", branch.condition)
    }

    @Test
    fun `DefaultBranch carries an id and label but no condition field`() {
        val branch =
            DefaultBranch(
                id = "br-fallback",
                label = "Manual review",
            )
        assertEquals("DEFAULT", branch.kindName)
        assertEquals("Manual review", branch.label)
    }

    @Test
    fun `UnconditionalBranch carries id and label and no condition`() {
        val branch =
            UnconditionalBranch(
                id = "br-it",
                label = "IT prep",
            )
        assertEquals("UNCONDITIONAL", branch.kindName)
        assertEquals("IT prep", branch.label)
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
