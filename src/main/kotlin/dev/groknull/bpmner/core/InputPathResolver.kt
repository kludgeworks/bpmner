/*
 * Copyright (c) 2026 The Project Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package dev.groknull.bpmner.core

import com.google.devtools.build.runfiles.Runfiles
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

@Component
internal class InputPathResolver private constructor(
    private val cwdProvider: () -> Path,
    private val runfilesLoader: () -> Runfiles? = defaultRunfilesLoader(),
) {
    @Autowired
    constructor() : this(
        cwdProvider = { currentWorkingDirectory(System.getenv(), System.getProperty("user.dir")) },
    )

    internal constructor(
        cwd: Path,
        runfilesLoader: () -> Runfiles? = defaultRunfilesLoader(),
    ) : this(
        cwdProvider = { cwd },
        runfilesLoader = runfilesLoader,
    )

    internal constructor(
        environment: Map<String, String>,
        userDir: String,
        runfilesLoader: () -> Runfiles? = defaultRunfilesLoader(),
    ) : this(
        cwdProvider = { currentWorkingDirectory(environment, userDir) },
        runfilesLoader = runfilesLoader,
    )

    private val cwd: Path by lazy(cwdProvider)

    fun readUtf8(rawInput: String): String = resolve(rawInput).readText(StandardCharsets.UTF_8)

    fun resolveOutputPath(rawInput: String): Path {
        val path = Path.of(rawInput)
        return if (path.isAbsolute) path else cwd.resolve(path).normalize()
    }

    fun resolve(rawInput: String): Path {
        val filesystemPath = filesystemCandidate(rawInput)
        if (Files.exists(filesystemPath)) {
            return filesystemPath
        }

        val attemptedLocations =
            mutableListOf(
                "filesystem path ${filesystemPath.toAbsolutePath().normalize()}",
            )

        if (!Path.of(rawInput).isAbsolute()) {
            runfilesLoader()?.let { runfiles ->
                findInRunfiles(rawInput, runfiles, attemptedLocations)?.let { return it }
            }
        }

        throw IllegalArgumentException(
            buildString {
                append("Input file '")
                append(rawInput)
                append("' was not found. Tried: ")
                append(attemptedLocations.joinToString(", "))
            },
        )
    }

    private fun findInRunfiles(
        rawInput: String,
        runfiles: Runfiles,
        attemptedLocations: MutableList<String>,
    ): Path? {
        for (key in listOf(rawInput, "_main/$rawInput", "bpmner/$rawInput")) {
            val resolved =
                try {
                    runfiles.rlocation(key)
                } catch (_: IllegalArgumentException) {
                    attemptedLocations += "Bazel runfile $key (invalid runfiles key)"
                    null
                }
            if (resolved != null) {
                attemptedLocations += "Bazel runfile $key -> $resolved"
                val candidate = Path.of(resolved)
                if (Files.exists(candidate)) return candidate
            }
        }
        return null
    }

    private fun filesystemCandidate(rawInput: String): Path {
        val path = Path.of(rawInput)
        return if (path.isAbsolute) path else cwd.resolve(path).normalize()
    }

    companion object {
        private const val BUILD_WORKING_DIRECTORY = "BUILD_WORKING_DIRECTORY"

        private fun currentWorkingDirectory(
            environment: Map<String, String>,
            userDir: String,
        ): Path =
            environment[BUILD_WORKING_DIRECTORY]
                ?.takeIf { it.isNotBlank() }
                ?.let { Path.of(it).toAbsolutePath().normalize() }
                ?: Path.of(userDir).toAbsolutePath().normalize()

        private fun defaultRunfilesLoader(): () -> Runfiles? =
            {
                try {
                    Runfiles.preload().withSourceRepository("")
                } catch (_: IOException) {
                    null
                }
            }
    }
}
