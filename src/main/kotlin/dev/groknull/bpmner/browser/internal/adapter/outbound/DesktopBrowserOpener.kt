/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.browser.internal.adapter.outbound

import dev.groknull.bpmner.browser.BrowserOpenOutcome
import dev.groknull.bpmner.browser.BrowserOpenPort
import org.jmolecules.architecture.hexagonal.SecondaryAdapter
import org.springframework.stereotype.Service
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.io.IOException
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private const val BROWSE_TIMEOUT_SECONDS = 30L

@SecondaryAdapter
@Service
internal open class DesktopBrowserOpener(
    /**
     * Returns true when a Desktop BROWSE action is available in this environment.
     * Production default: guarded check per ARCHITECTURE.md §85–93.
     */
    private val isBrowseAvailable: () -> Boolean = {
        !GraphicsEnvironment.isHeadless() &&
            Desktop.isDesktopSupported() &&
            Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
    },
    /**
     * Performs the actual browse call. May throw IOException or
     * UnsupportedOperationException; all other throwables are wrapped in IOException.
     */
    private val desktopBrowse: (URI) -> Unit = { uri ->
        Desktop.getDesktop().browse(uri)
    },
    private val osNameSupplier: () -> String = { System.getProperty("os.name").lowercase() },
    private val launchWithProcessBuilder: (Array<out String>) -> Int = launch@{ cmd ->
        // Catch IOException from start() when the executable is not found (e.g. xdg-open absent on
        // minimal Linux). Map to -1 so the caller maps it to Failed without throwing.
        val process = try {
            ProcessBuilder(*cmd).start()
        } catch (_: IOException) {
            return@launch -1
        }
        // Drain stdout to prevent OS pipe-buffer deadlock.
        val drain = Thread {
            try {
                process.inputStream.bufferedReader().use { it.readText() }
            } catch (_: Exception) { /* discard */ }
        }
        drain.isDaemon = true
        drain.start()
        val finished = try {
            process.waitFor(BROWSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            false
        }
        if (finished) {
            process.exitValue()
        } else {
            process.destroyForcibly()
            -1
        }
    },
) : BrowserOpenPort {

    override fun open(target: Path): BrowserOpenOutcome = if (isBrowseAvailable()) {
        browseWithDesktop(target)
    } else {
        launchWithProcessBuilderFallback(osNameSupplier(), target)
    }

    private fun browseWithDesktop(target: Path): BrowserOpenOutcome = try {
        desktopBrowse(target.toUri())
        BrowserOpenOutcome.Opened
    } catch (e: IOException) {
        BrowserOpenOutcome.Failed("IOException while browsing: ${e.message}")
    } catch (e: UnsupportedOperationException) {
        BrowserOpenOutcome.Unsupported(e.message ?: "Desktop browse action not supported")
    }

    private fun launchWithProcessBuilderFallback(osName: String, target: Path): BrowserOpenOutcome {
        val uri = target.toUri().toString()
        val osNameLower = osName.lowercase()
        return when {
            osNameLower.contains("mac") || osNameLower.contains("darwin") -> {
                val code = launchWithProcessBuilder(arrayOf("open", target.toString()))
                if (code == 0) {
                    BrowserOpenOutcome.Opened
                } else {
                    BrowserOpenOutcome.Failed("open command exited with code $code")
                }
            }
            osNameLower.contains("win") -> {
                // Use rundll32 to avoid cmd.exe shell parsing / metacharacter injection.
                val code = launchWithProcessBuilder(arrayOf("rundll32", "url.dll,FileProtocolHandler", uri))
                if (code == 0) {
                    BrowserOpenOutcome.Opened
                } else {
                    BrowserOpenOutcome.Failed("rundll32 exited with code $code")
                }
            }
            osNameLower.contains("nix") || osNameLower.contains("nux") || osNameLower.contains("linux") -> {
                val code = launchWithProcessBuilder(arrayOf("xdg-open", target.toString()))
                if (code == 0) {
                    BrowserOpenOutcome.Opened
                } else {
                    BrowserOpenOutcome.Failed("xdg-open exited with code $code")
                }
            }
            else -> BrowserOpenOutcome.Unsupported("Unknown operating system: $osName")
        }
    }
}
