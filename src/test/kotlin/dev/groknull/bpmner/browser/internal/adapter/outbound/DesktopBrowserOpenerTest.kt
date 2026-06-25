/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.browser.internal.adapter.outbound

import dev.groknull.bpmner.browser.BrowserOpenOutcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.Path

@Suppress(
    "EmptyFunctionBlock",
    "FunctionOnlyReturningConstant",
    "UnusedParameter",
)
class DesktopBrowserOpenerTest {

    @Test
    fun `opened outcome when browse succeeds`() {
        val opener = DesktopBrowserOpener(
            desktopSupplier = { DesktopOpenerMock() },
            osNameSupplier = { "linux" },
            launchWithProcessBuilder = { error("should not use fallback") },
        )
        val target = Path.of("/tmp/test.bpmn")

        val outcome = opener.open(target)

        assertThat(outcome).isInstanceOf(BrowserOpenOutcome.Opened::class.java)
    }

    @Test
    fun `unsupported outcome when browse action not supported`() {
        val opener = DesktopBrowserOpener(
            desktopSupplier = { DesktopOpenerMock(browseSupported = false) },
            osNameSupplier = { "linux" },
            launchWithProcessBuilder = { error("should not use fallback") },
        )
        val target = Path.of("/tmp/test.bpmn")

        val outcome = opener.open(target)

        assertThat(outcome).isInstanceOf(BrowserOpenOutcome.Unsupported::class.java)
    }

    @Test
    fun `failed outcome when browse throws IOException`() {
        val opener = DesktopBrowserOpener(
            desktopSupplier = { DesktopOpenerMock(browse = { throw IOException("network error") }) },
            osNameSupplier = { "linux" },
            launchWithProcessBuilder = { error("should not use fallback") },
        )
        val target = Path.of("/tmp/test.bpmn")

        val outcome = opener.open(target)

        assertThat(outcome).isInstanceOf(BrowserOpenOutcome.Failed::class.java)
        assertThat((outcome as BrowserOpenOutcome.Failed).reason).contains("IOException")
    }

    @Test
    fun `unsupported outcome when browse throws UnsupportedOperationException`() {
        val opener = DesktopBrowserOpener(
            desktopSupplier = { DesktopOpenerMock(browse = { throw UnsupportedOperationException("not supported") }) },
            osNameSupplier = { "linux" },
            launchWithProcessBuilder = { error("should not use fallback") },
        )
        val target = Path.of("/tmp/test.bpmn")

        val outcome = opener.open(target)

        assertThat(outcome).isInstanceOf(BrowserOpenOutcome.Unsupported::class.java)
    }

    @Test
    fun `macOS fallback uses open command`() {
        var capturedCommand: Array<out String>? = null

        @Suppress("UnusedPrivateProperty")
        var launcherCalled = false
        val opener = DesktopBrowserOpener(
            desktopSupplier = { null },
            osNameSupplier = { "Mac OS X" },
            launchWithProcessBuilder = { cmd ->
                capturedCommand = cmd
                0
            },
        )
        val target = Path.of("/tmp/my file.bpmn")

        opener.open(target)

        assertThat(capturedCommand).isNotNull
        assertThat(capturedCommand).containsExactly("open", "/tmp/my file.bpmn")
    }

    @Test
    fun `Windows fallback uses cmd start command with uri`() {
        var capturedCommand: Array<out String>? = null
        val opener = DesktopBrowserOpener(
            desktopSupplier = { null },
            osNameSupplier = { "Windows 10" },
            launchWithProcessBuilder = { cmd ->
                capturedCommand = cmd
                0
            },
        )
        // Use an absolute Unix path that will convert to a valid URI
        val target = Path.of("/tmp/diagram.bpmn")

        opener.open(target)

        assertThat(capturedCommand).isNotNull
        // Windows command expects file:/// URI format
        assertThat(capturedCommand).containsExactly("cmd", "/c", "start", "\"\"", "file:///tmp/diagram.bpmn")
    }

    @Test
    fun `Linux fallback uses xdg-open command`() {
        var capturedCommand: Array<out String>? = null
        val opener = DesktopBrowserOpener(
            desktopSupplier = { null },
            osNameSupplier = { "Linux" },
            launchWithProcessBuilder = { cmd ->
                capturedCommand = cmd
                0
            },
        )
        val target = Path.of("/tmp/diagram.bpmn")

        opener.open(target)

        assertThat(capturedCommand).isNotNull
        assertThat(capturedCommand).containsExactly("xdg-open", "/tmp/diagram.bpmn")
    }

    @Test
    fun `failed outcome when fallback command returns non-zero exit code`() {
        val opener = DesktopBrowserOpener(
            desktopSupplier = { null },
            osNameSupplier = { "linux" },
            launchWithProcessBuilder = { _ -> 1 },
        )
        val target = Path.of("/tmp/test.bpmn")

        val outcome = opener.open(target)

        assertThat(outcome).isInstanceOf(BrowserOpenOutcome.Failed::class.java)
        assertThat((outcome as BrowserOpenOutcome.Failed).reason).contains("exited with code 1")
    }

    @Test
    fun `unknown os returns unsupported outcome`() {
        val opener = DesktopBrowserOpener(
            desktopSupplier = { null },
            osNameSupplier = { "UnknownOS" },
            launchWithProcessBuilder = { error("should not be called") },
        )
        val target = Path.of("/tmp/test.bpmn")

        val outcome = opener.open(target)

        assertThat(outcome).isInstanceOf(BrowserOpenOutcome.Unsupported::class.java)
    }

    @Test
    fun `path with spaces uses Path toUri which encodes as %20`() {
        var capturedUri: String? = null
        val opener = DesktopBrowserOpener(
            desktopSupplier = {
                DesktopOpenerMock(browse = { uri: Any? ->
                    capturedUri = uri.toString()
                })
            },
            osNameSupplier = { "linux" },
            launchWithProcessBuilder = { error("should not use fallback") },
        )
        val target = Path.of("/tmp/my file.bpmn")

        opener.open(target)

        // Path.toUri() should encode space as %20, not URLEncoder
        assertThat(capturedUri).contains("my%20file")
        assertThat(capturedUri).doesNotContain("+")
    }

    private object DesktopOpenerMock {
        private const val BROWSE_ACTION = "BROWSE"

        private fun browseImpl(uri: Any?) {}

        private val desktop = object {
            fun browse(uri: Any?) {
                DesktopOpenerMock.browseImpl(uri)
            }

            fun isDesktopSupported(): Boolean = true

            fun isSupported(action: Any): Boolean = action.toString() == BROWSE_ACTION
        }

        operator fun invoke(): Any = desktop

        operator fun invoke(browseSupported: Boolean): Any = object {
            fun browse(uri: Any?) {
                if (!browseSupported) {
                    throw UnsupportedOperationException("Browse action not supported")
                }
            }

            fun isDesktopSupported(): Boolean = true
            fun isSupported(action: Any): Boolean = browseSupported
        }

        operator fun invoke(browse: (Any?) -> Unit): Any = object {
            fun browse(uri: Any?) {
                browse(uri)
            }

            fun isDesktopSupported(): Boolean = true
            fun isSupported(action: Any): Boolean = true
        }
    }
}
