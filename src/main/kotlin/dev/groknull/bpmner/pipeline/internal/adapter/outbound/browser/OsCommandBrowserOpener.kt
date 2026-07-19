/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.pipeline.internal.adapter.outbound.browser

import dev.groknull.bpmner.pipeline.BrowserOpenOutcome
import dev.groknull.bpmner.pipeline.BrowserOpenPort
import org.jmolecules.architecture.onion.simplified.InfrastructureRing
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private const val LAUNCH_TIMEOUT_SECONDS = 30L

/**
 * Opens a file in the OS default browser by shelling out to the platform launcher:
 * `open` (macOS), `xdg-open` (Linux), or `rundll32` (Windows).
 *
 * This is a best-effort, nice-to-have. If the OS is unrecognised or the command fails, the caller
 * still has the generated BPMN and the written HTML preview file. We deliberately avoid
 * `java.awt.Desktop`: the app runs headless (Spring Boot sets `java.awt.headless=true`), where
 * `Desktop.browse` throws `HeadlessException` — the OS launcher command works regardless.
 */
@InfrastructureRing
@Service
internal open class OsCommandBrowserOpener(
    private val osNameSupplier: () -> String = { System.getProperty("os.name") ?: "unknown" },
    private val launch: (Array<String>) -> Int = ::runLauncher,
) : BrowserOpenPort {

    private val logger = LoggerFactory.getLogger(OsCommandBrowserOpener::class.java)

    override fun open(target: Path): BrowserOpenOutcome {
        val osName = osNameSupplier()
        val command = commandFor(osName, target)
            ?: run {
                logger.warn("[preview] cannot open browser — unrecognised OS '{}'", osName)
                return BrowserOpenOutcome.Failed("unsupported operating system: $osName")
            }
        logger.debug("[preview] launching browser: {}", command.joinToString(" "))
        val exit = launch(command)
        return if (exit == 0) {
            BrowserOpenOutcome.Opened
        } else {
            logger.warn("[preview] browser launcher '{}' exited with code {}", command.first(), exit)
            BrowserOpenOutcome.Failed("${command.first()} exited with code $exit")
        }
    }

    private fun commandFor(osName: String, target: Path): Array<String>? {
        val os = osName.lowercase()
        return when {
            os.contains("mac") || os.contains("darwin") -> arrayOf("open", target.toString())
            // rundll32 (not cmd.exe) avoids shell parsing / metacharacter injection.
            os.contains("win") -> arrayOf("rundll32", "url.dll,FileProtocolHandler", target.toUri().toString())
            os.contains("nix") || os.contains("nux") || os.contains("linux") -> arrayOf("xdg-open", target.toString())
            else -> null
        }
    }

    private companion object {
        // Start the launcher and wait briefly for it to hand off to the browser. Output is
        // discarded (no pipe-buffer deadlock). Anything that goes wrong maps to -1 so the
        // best-effort preview never throws: IOException (command absent on PATH) and
        // SecurityException (process creation denied) both surface as a failed launch.
        fun runLauncher(command: Array<String>): Int = try {
            val process = ProcessBuilder(*command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            if (process.waitFor(LAUNCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.exitValue()
            } else {
                process.destroyForcibly()
                -1
            }
        } catch (_: IOException) {
            -1
        } catch (_: SecurityException) {
            -1
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            -1
        }
    }
}
