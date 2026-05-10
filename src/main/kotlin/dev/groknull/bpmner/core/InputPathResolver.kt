package dev.groknull.bpmner.core

import com.google.devtools.build.runfiles.Runfiles
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

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
        private const val BUILD_WORKING_DIRECTORY = "BUILD_WORKING_DIRECTORY"

        private fun currentWorkingDirectory(environment: Map<String, String>, userDir: String): Path =
            environment[BUILD_WORKING_DIRECTORY]
                ?.takeIf { it.isNotBlank() }
                ?.let { Path.of(it).toAbsolutePath().normalize() }
                ?: Path.of(userDir).toAbsolutePath().normalize()

        private fun defaultRunfilesLoader(): () -> Runfiles? = {
            try {
                Runfiles.preload().withSourceRepository("")
            } catch (_: IOException) {
                null
            }
        }
    }
}
