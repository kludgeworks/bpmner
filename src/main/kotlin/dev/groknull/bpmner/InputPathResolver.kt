package dev.groknull.bpmner

import com.google.devtools.build.runfiles.Runfiles
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

internal class InputPathResolver(
    private val cwd: Path = Path.of("").toAbsolutePath(),
    private val runfilesLoader: () -> Runfiles? = defaultRunfilesLoader(),
) {

    fun readUtf8(rawInput: String): String = resolve(rawInput).readText(StandardCharsets.UTF_8)

    /**
     * Resolves a path for output purposes, anchored to the current working directory.
     * Does not check runfiles.
     */
    fun resolveOutputPath(rawInput: String): Path {
        val path = Path.of(rawInput)
        return if (path.isAbsolute) path else cwd.resolve(path).normalize()
    }

    fun resolve(rawInput: String): Path {
        val filesystemPath = filesystemCandidate(rawInput)
        if (Files.exists(filesystemPath)) {
            return filesystemPath
        }

        val attemptedLocations = mutableListOf(
            "filesystem path ${filesystemPath.toAbsolutePath().normalize()}",
        )

        if (!Path.of(rawInput).isAbsolute()) {
            val runfiles = runfilesLoader()
            if (runfiles != null) {
                val runfilesKeys = listOf(
                    rawInput,
                    "_main/$rawInput",
                    "bpmner/$rawInput",
                )

                for (key in runfilesKeys) {
                    val resolved = try {
                        runfiles.rlocation(key)
                    } catch (_: IllegalArgumentException) {
                        attemptedLocations += "Bazel runfile $key (invalid runfiles key)"
                        null
                    }
                    if (resolved != null) {
                        attemptedLocations += "Bazel runfile $key -> $resolved"
                    }
                    if (resolved != null) {
                        val candidate = Path.of(resolved)
                        if (Files.exists(candidate)) {
                            return candidate
                        }
                    }
                }
            }
        }

        throw IllegalArgumentException(
            buildString {
                append("Input file '")
                append(rawInput)
                append("' was not found. Tried: ")
                append(attemptedLocations.joinToString(", "))
            }
        )
    }

    private fun filesystemCandidate(rawInput: String): Path {
        val path = Path.of(rawInput)
        return if (path.isAbsolute) path else cwd.resolve(path).normalize()
    }

    companion object {
        private fun defaultRunfilesLoader(): () -> Runfiles? = {
            try {
                Runfiles.preload().withSourceRepository("")
            } catch (_: IOException) {
                null
            }
        }
    }
}
