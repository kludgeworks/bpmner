/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.adapter.inbound

import com.embabel.agent.shell.ShellCommands
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.ObjectProvider

class BpmnShellCommandsTest {
    private fun commandDelegatingTo(shellCommands: ShellCommands): BpmnShellCommands {
        @Suppress("UNCHECKED_CAST")
        val provider = mock(ObjectProvider::class.java) as ObjectProvider<ShellCommands>
        `when`(provider.getObject()).thenReturn(shellCommands)
        return BpmnShellCommands(provider)
    }

    @Test
    fun `generate delegates to embabel execute forcing open mode`() {
        val shellCommands = mock(ShellCommands::class.java)
        // Forced open mode (2nd arg = true); every other flag off except the default showPlanning.
        `when`(
            shellCommands.execute("Make toast", true, false, false, false, false, false, false, true, null),
        ).thenReturn("Generated BPMN")

        val result = commandDelegatingTo(shellCommands).generate("Make toast")

        assertEquals("Generated BPMN", result)
        verify(shellCommands).execute("Make toast", true, false, false, false, false, false, false, true, null)
    }

    @Test
    fun `an explicit output file is conveyed as a directive in the intent`() {
        val shellCommands = mock(ShellCommands::class.java)
        val expectedIntent = "Make toast\n\nWrite the generated BPMN to the file: toast.bpmn"
        `when`(
            shellCommands.execute(expectedIntent, true, false, false, false, false, false, false, true, null),
        ).thenReturn("ok")

        val result = commandDelegatingTo(shellCommands).generate("Make toast", "toast.bpmn")

        assertEquals("ok", result)
        verify(shellCommands).execute(expectedIntent, true, false, false, false, false, false, false, true, null)
    }

    @Test
    fun `the written output file is echoed as the final line`() {
        val shellCommands = mock(ShellCommands::class.java)
        // Mirrors how embabel renders the result: BpmnResult.content (with the file) sits above the
        // cost/tool-usage summary, so the filename is easy to miss without a trailing echo.
        val rendered =
            """
            You asked: Make toast
            Generated BPMN → toast.bpmn (842 chars).

            LLMs used: [mistral-large-2411] across 4 calls
            Cost: ${'$'}0.04
            Tool usage:
            """.trimIndent()
        `when`(
            shellCommands.execute("Make toast", true, false, false, false, false, false, false, true, null),
        ).thenReturn(rendered)

        val result = commandDelegatingTo(shellCommands).generate("Make toast")

        assertTrue(result.endsWith("Wrote BPMN to: toast.bpmn"), "result was:\n$result")
    }

    @Test
    fun `no trailing line is added when nothing was generated`() {
        val shellCommands = mock(ShellCommands::class.java)
        `when`(
            shellCommands.execute("Make toast", true, false, false, false, false, false, false, true, null),
        ).thenReturn("I'm sorry. I don't know how to proceed.")

        val result = commandDelegatingTo(shellCommands).generate("Make toast")

        assertEquals("I'm sorry. I don't know how to proceed.", result)
    }
}
