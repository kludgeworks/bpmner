/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.browser.internal.adapter.outbound

import dev.groknull.bpmner.browser.BrowserOpenOutcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI
import java.nio.file.Path

class DesktopBrowserOpenerTest {

    // ---------------------------------------------------------------------------
    // Desktop browse path
    // ---------------------------------------------------------------------------

    @Test
    fun `opened outcome when desktop browse succeeds`() {
        val opener = DesktopBrowserOpener(
            isBrowseAvailable = { true },
            desktopBrowse = { /* no-op – success */ },
            osNameSupplier = { "linux" },
            launchWithProcessBuilder = { error("fallback must not be called") },
        )

        val outcome = opener.open(Path.of("/tmp/test.bpmn"))

        assertThat(outcome).isInstanceOf(BrowserOpenOutcome.Opened::class.java)
    }

    @Test
    fun `failed outcome when desktop browse throws IOException`() {
        val opener = DesktopBrowserOpener(
            isBrowseAvailable = { true },
            desktopBrowse = { throw IOException("network error") },
            osNameSupplier = { "linux" },
            launchWithProcessBuilder = { error("fallback must not be called") },
        )

        val outcome = opener.open(Path.of("/tmp/test.bpmn"))

        assertThat(outcome).isInstanceOf(BrowserOpenOutcome.Failed::class.java)
        assertThat((outcome as BrowserOpenOutcome.Failed).reason).contains("IOException")
    }

    @Test
    fun `unsupported outcome when desktop browse throws UnsupportedOperationException`() {
        val opener = DesktopBrowserOpener(
            isBrowseAvailable = { true },
            desktopBrowse = { throw UnsupportedOperationException("not supported") },
            osNameSupplier = { "linux" },
            launchWithProcessBuilder = { error("fallback must not be called") },
        )

        val outcome = opener.open(Path.of("/tmp/test.bpmn"))

        assertThat(outcome).isInstanceOf(BrowserOpenOutcome.Unsupported::class.java)
        assertThat((outcome as BrowserOpenOutcome.Unsupported).reason).contains("not supported")
    }

    @Test
    fun `falls through to ProcessBuilder fallback when Desktop BROWSE is unavailable`() {
        // isBrowseAvailable = false → Desktop path is skipped, OS fallback runs.
        var capturedCommand: Array<out String>? = null
        val opener = DesktopBrowserOpener(
            isBrowseAvailable = { false },
            desktopBrowse = { error("desktop browse must not be called") },
            osNameSupplier = { "linux" },
            launchWithProcessBuilder = { cmd ->
                capturedCommand = cmd
                0
            },
        )

        val outcome = opener.open(Path.of("/tmp/test.bpmn"))

        assertThat(outcome).isInstanceOf(BrowserOpenOutcome.Opened::class.java)
        assertThat(capturedCommand).containsExactly("xdg-open", "/tmp/test.bpmn")
    }

    @Test
    fun `path with spaces uses Path toUri which encodes as percent-20`() {
        var capturedUri: URI? = null
        val opener = DesktopBrowserOpener(
            isBrowseAvailable = { true },
            desktopBrowse = { uri -> capturedUri = uri },
            osNameSupplier = { "linux" },
            launchWithProcessBuilder = { error("fallback must not be called") },
        )

        opener.open(Path.of("/tmp/my file.bpmn"))

        // Path.toUri() encodes spaces as %20, never as '+' (URLEncoder behaviour).
        assertThat(capturedUri.toString()).contains("my%20file")
        assertThat(capturedUri.toString()).doesNotContain("+")
    }

    // ---------------------------------------------------------------------------
    // ProcessBuilder fallback — command array assertions
    // ---------------------------------------------------------------------------

    @Test
    fun `macOS fallback uses open command with path`() {
        var capturedCommand: Array<out String>? = null
        val opener = DesktopBrowserOpener(
            isBrowseAvailable = { false },
            desktopBrowse = { error("desktop browse must not be called") },
            osNameSupplier = { "Mac OS X" },
            launchWithProcessBuilder = { cmd ->
                capturedCommand = cmd
                0
            },
        )

        opener.open(Path.of("/tmp/my file.bpmn"))

        assertThat(capturedCommand).containsExactly("open", "/tmp/my file.bpmn")
    }

    @Test
    fun `Windows fallback uses rundll32 to avoid shell metacharacter injection`() {
        var capturedCommand: Array<out String>? = null
        val opener = DesktopBrowserOpener(
            isBrowseAvailable = { false },
            desktopBrowse = { error("desktop browse must not be called") },
            osNameSupplier = { "Windows 10" },
            launchWithProcessBuilder = { cmd ->
                capturedCommand = cmd
                0
            },
        )

        opener.open(Path.of("/tmp/diagram.bpmn"))

        assertThat(capturedCommand)
            .containsExactly("rundll32", "url.dll,FileProtocolHandler", "file:///tmp/diagram.bpmn")
    }

    @Test
    fun `Linux fallback uses xdg-open command with path`() {
        var capturedCommand: Array<out String>? = null
        val opener = DesktopBrowserOpener(
            isBrowseAvailable = { false },
            desktopBrowse = { error("desktop browse must not be called") },
            osNameSupplier = { "Linux" },
            launchWithProcessBuilder = { cmd ->
                capturedCommand = cmd
                0
            },
        )

        opener.open(Path.of("/tmp/diagram.bpmn"))

        assertThat(capturedCommand).containsExactly("xdg-open", "/tmp/diagram.bpmn")
    }

    @Test
    fun `failed outcome when ProcessBuilder fallback exits with non-zero code`() {
        val opener = DesktopBrowserOpener(
            isBrowseAvailable = { false },
            desktopBrowse = { error("desktop browse must not be called") },
            osNameSupplier = { "linux" },
            launchWithProcessBuilder = { 1 },
        )

        val outcome = opener.open(Path.of("/tmp/test.bpmn"))

        assertThat(outcome).isInstanceOf(BrowserOpenOutcome.Failed::class.java)
        assertThat((outcome as BrowserOpenOutcome.Failed).reason).contains("exited with code 1")
    }

    @Test
    fun `unsupported outcome for unknown operating system`() {
        val opener = DesktopBrowserOpener(
            isBrowseAvailable = { false },
            desktopBrowse = { error("desktop browse must not be called") },
            osNameSupplier = { "UnknownOS" },
            launchWithProcessBuilder = { error("fallback must not be called") },
        )

        val outcome = opener.open(Path.of("/tmp/test.bpmn"))

        assertThat(outcome).isInstanceOf(BrowserOpenOutcome.Unsupported::class.java)
    }
}
