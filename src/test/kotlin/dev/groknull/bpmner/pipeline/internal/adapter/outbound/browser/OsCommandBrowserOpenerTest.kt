/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.pipeline.internal.adapter.outbound.browser

import dev.groknull.bpmner.pipeline.internal.adapter.outbound.browser.BrowserOpenOutcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path

class OsCommandBrowserOpenerTest {

    @Test
    fun `macOS uses the open command with the file path`() {
        var captured: Array<String>? = null
        val opener = OsCommandBrowserOpener(
            osNameSupplier = { "Mac OS X" },
            launch = {
                captured = it
                0
            },
        )

        val outcome = opener.open(Path.of("/tmp/my file.bpmn"))

        assertThat(outcome).isInstanceOf(BrowserOpenOutcome.Opened::class.java)
        assertThat(captured).containsExactly("open", "/tmp/my file.bpmn")
    }

    @Test
    fun `Linux uses xdg-open with the file path`() {
        var captured: Array<String>? = null
        val opener = OsCommandBrowserOpener(
            osNameSupplier = { "Linux" },
            launch = {
                captured = it
                0
            },
        )

        opener.open(Path.of("/tmp/diagram.bpmn"))

        assertThat(captured).containsExactly("xdg-open", "/tmp/diagram.bpmn")
    }

    @Test
    fun `Windows uses rundll32 with a file URI to avoid shell injection`() {
        var captured: Array<String>? = null
        val opener = OsCommandBrowserOpener(
            osNameSupplier = { "Windows 10" },
            launch = {
                captured = it
                0
            },
        )

        opener.open(Path.of("/tmp/diagram.bpmn"))

        assertThat(captured).containsExactly("rundll32", "url.dll,FileProtocolHandler", "file:///tmp/diagram.bpmn")
    }

    @Test
    fun `opened when the launcher exits zero`() {
        val opener = OsCommandBrowserOpener(osNameSupplier = { "linux" }, launch = { 0 })

        assertThat(opener.open(Path.of("/tmp/test.bpmn"))).isInstanceOf(BrowserOpenOutcome.Opened::class.java)
    }

    @Test
    fun `failed when the launcher exits non-zero`() {
        val opener = OsCommandBrowserOpener(osNameSupplier = { "linux" }, launch = { 1 })

        val outcome = opener.open(Path.of("/tmp/test.bpmn"))

        assertThat(outcome).isInstanceOf(BrowserOpenOutcome.Failed::class.java)
        assertThat((outcome as BrowserOpenOutcome.Failed).reason).contains("exited with code 1")
    }

    @Test
    fun `failed when the launcher returns -1 (command not found)`() {
        // The production launcher maps an IOException from ProcessBuilder.start() (e.g. xdg-open
        // absent on PATH) to -1; this verifies that -1 surfaces as Failed.
        val opener = OsCommandBrowserOpener(osNameSupplier = { "linux" }, launch = { -1 })

        val outcome = opener.open(Path.of("/tmp/test.bpmn"))

        assertThat(outcome).isInstanceOf(BrowserOpenOutcome.Failed::class.java)
        assertThat((outcome as BrowserOpenOutcome.Failed).reason).contains("exited with code -1")
    }

    @Test
    fun `failed for an unrecognised operating system`() {
        val opener = OsCommandBrowserOpener(osNameSupplier = { "UnknownOS" }, launch = { error("must not launch") })

        val outcome = opener.open(Path.of("/tmp/test.bpmn"))

        assertThat(outcome).isInstanceOf(BrowserOpenOutcome.Failed::class.java)
        assertThat((outcome as BrowserOpenOutcome.Failed).reason).contains("unsupported operating system")
    }
}
