/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.adapter.inbound

import com.embabel.agent.shell.ShellCommands
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.ObjectProvider

class BpmnShellCommandsTest {
    @Test
    fun `generate delegates to embabel execute forcing open mode`() {
        val shellCommands = mock(ShellCommands::class.java)

        @Suppress("UNCHECKED_CAST")
        val provider = mock(ObjectProvider::class.java) as ObjectProvider<ShellCommands>
        `when`(provider.getObject()).thenReturn(shellCommands)
        // Forced open mode (2nd arg = true); every other flag off except the default showPlanning.
        `when`(
            shellCommands.execute("Make toast", true, false, false, false, false, false, false, true, null),
        ).thenReturn("Generated BPMN")

        val result = BpmnShellCommands(provider).generate("Make toast")

        assertEquals("Generated BPMN", result)
        verify(shellCommands).execute("Make toast", true, false, false, false, false, false, false, true, null)
    }
}
