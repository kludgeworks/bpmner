package dev.groknull.bpmner

import com.google.devtools.build.runfiles.Runfiles
import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.Ordered
import org.springframework.core.PriorityOrdered
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

internal class InputPathResolver(
    private val cwd: Path = bazelWorkingDirOrCwd(),
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
        fun bazelWorkingDirOrCwd(): Path {
            val bwd = System.getenv("BUILD_WORKING_DIRECTORY")
            return if (bwd != null) Path.of(bwd) else Path.of("").toAbsolutePath().normalize()
        }

        private fun defaultRunfilesLoader(): () -> Runfiles? = {
            try {
                Runfiles.preload().withSourceRepository("")
            } catch (_: IOException) {
                null
            }
        }
    }
}

/**
 * Spring Boot environment post-processor that resolves relative paths in key properties
 * against the directory from which 'bazel run' was invoked.
 *
 * This ensures that logs are written back to the user's workspace rather than the
 * ephemeral runfiles sandbox.
 */
class BazelWorkingDirectoryEnvironmentPostProcessor : EnvironmentPostProcessor, PriorityOrdered {
    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication?) {
        val bwd = System.getenv("BUILD_WORKING_DIRECTORY") ?: return
        val cwd = Path.of(bwd)

        // Resolve the logging file override if it is a relative path.
        environment.getProperty("bpmner.logging.file")?.let { logFile ->
            val path = Path.of(logFile)
            if (!path.isAbsolute) {
                val resolved = cwd.resolve(path).normalize().toAbsolutePath().toString()
                environment.propertySources.addFirst(
                    MapPropertySource(
                        "bazelWorkingDirectoryResolver",
                        mapOf("bpmner.logging.file" to resolved)
                    )
                )
            }
        }
    }

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE
}
