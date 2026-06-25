/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.browser.internal.adapter.outbound

import dev.groknull.bpmner.browser.BrowserOpenOutcome
import dev.groknull.bpmner.browser.BrowserOpenPort
import org.jmolecules.architecture.hexagonal.SecondaryAdapter
import org.springframework.stereotype.Service
import java.io.IOException
import java.lang.reflect.Method
import java.nio.file.Path

@SecondaryAdapter
@Service
internal open class DesktopBrowserOpener(
    private val desktopSupplier: () -> Any? = { DesktopBrowserOpener.getDefaultDesktop() },
    private val osNameSupplier: () -> String = { System.getProperty("os.name").lowercase() },
    private val launchWithProcessBuilder: (Array<out String>) -> Int = { cmd ->
        ProcessBuilder(*cmd).redirectErrorStream(true).start().waitFor()
    },
) : BrowserOpenPort {

    override fun open(target: Path): BrowserOpenOutcome {
        val osName = osNameSupplier()
        val desktop = desktopSupplier()

        return if (desktop != null && isDesktopSupported(desktop)) {
            browseWithDesktop(desktop, target)
        } else {
            launchWithProcessBuilderFallback(osName, target)
        }
    }

    @Suppress("SwallowedException")
    private fun browseWithDesktop(desktop: Any, target: Path): BrowserOpenOutcome {
        return try {
            browse(desktop, target)
            BrowserOpenOutcome.Opened
        } catch (e: IOException) {
            BrowserOpenOutcome.Failed("IOException while browsing: ${e.message}")
        } catch (e: UnsupportedOperationException) {
            BrowserOpenOutcome.Unsupported("Desktop browse action not supported")
        }
    }

    private fun launchWithProcessBuilderFallback(osName: String, target: Path): BrowserOpenOutcome {
        val uri = target.toUri().toString()
        val osNameLower = osName.lowercase()
        return when {
            osNameLower.contains("mac") || osNameLower.contains("darwin") -> {
                val result = launchWithProcessBuilder(arrayOf("open", target.toString()))
                if (result == 0) {
                    BrowserOpenOutcome.Opened
                } else {
                    BrowserOpenOutcome.Failed("open command exited with code $result")
                }
            }
            osNameLower.contains("win") -> {
                val result = launchWithProcessBuilder(arrayOf("cmd", "/c", "start", "\"\"", uri))
                if (result == 0) {
                    BrowserOpenOutcome.Opened
                } else {
                    BrowserOpenOutcome.Failed("cmd start exited with code $result")
                }
            }
            osNameLower.contains("nix") || osNameLower.contains("nux") || osNameLower.contains("linux") -> {
                val result = launchWithProcessBuilder(arrayOf("xdg-open", target.toString()))
                if (result == 0) {
                    BrowserOpenOutcome.Opened
                } else {
                    BrowserOpenOutcome.Failed("xdg-open exited with code $result")
                }
            }
            else -> BrowserOpenOutcome.Unsupported("Unknown operating system: $osName")
        }
    }

    companion object {
        @Suppress("SwallowedException")
        fun getDefaultDesktop(): Any? {
            return try {
                val desktopClass = Class.forName("java.awt.Desktop")
                val isDesktopSupported: Method = desktopClass.getMethod("isDesktopSupported")
                val getDesktop: Method = desktopClass.getMethod("getDesktop")

                if (isDesktopSupported.invoke(null) as Boolean) {
                    getDesktop.invoke(null)
                } else {
                    null
                }
            } catch (e: ClassNotFoundException) {
                null
            } catch (e: NoSuchMethodException) {
                null
            } catch (e: IllegalAccessException) {
                null
            }
        }
    }

    @Suppress("SwallowedException")
    private fun isDesktopSupported(desktop: Any?): Boolean {
        return try {
            val desktopClass = desktop?.javaClass ?: return false
            val method: Method = desktopClass.getMethod("isDesktopSupported")
            // Try instance method first, then static (for real Desktop class)
            try {
                method.invoke(desktop) as Boolean
            } catch (iae: IllegalArgumentException) {
                // Instance method failed, try static (Desktop.isDesktopSupported())
                method.invoke(null) as Boolean
            }
        } catch (e: NoSuchMethodException) {
            false
        } catch (e: IllegalAccessException) {
            false
        }
    }

    @Suppress("SwallowedException", "UnusedPrivateMember")
    private fun isBrowseSupported(desktop: Any?): Boolean {
        return try {
            val desktopClass = desktop?.javaClass ?: return false
            val browseActionClass = Class.forName("java.awt.Desktop\$Action")
            val browseAction = browseActionClass.enumConstants?.firstOrNull { it.toString() == "BROWSE" }
                ?: return false
            // Try to find isSupported with the enum type first, then with Object (for mocks)
            val method: Method = try {
                desktopClass.getMethod("isSupported", browseActionClass)
            } catch (e: NoSuchMethodException) {
                desktopClass.getMethod("isSupported", Any::class.java)
            }
            // Try instance method first, then static
            try {
                method.invoke(desktop, browseAction) as Boolean
            } catch (iae: IllegalArgumentException) {
                method.invoke(null, browseAction) as Boolean
            }
        } catch (e: NoSuchMethodException) {
            false
        } catch (e: IllegalAccessException) {
            false
        }
    }

    @Suppress("SwallowedException", "ThrowsCount")
    private fun browse(desktop: Any, target: Path) {
        val desktopClass = desktop.javaClass
        val uriClass = Class.forName("java.net.URI")
        val browse = try {
            desktopClass.getMethod("browse", uriClass)
        } catch (e: NoSuchMethodException) {
            desktopClass.methods.firstOrNull { it.name == "browse" && it.parameterCount == 1 }
                ?: throw IOException("Failed to browse: no browse method found")
        }
        try {
            browse.invoke(desktop, target.toUri())
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        } catch (e: IllegalAccessException) {
            throw IOException("Failed to browse: inaccessible method", e)
        }
    }
}
