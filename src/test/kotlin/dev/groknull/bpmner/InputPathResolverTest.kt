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

package dev.groknull.bpmner

import com.google.devtools.build.runfiles.Runfiles
import dev.groknull.bpmner.core.InputPathResolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.pathString
import kotlin.io.path.writeText

class InputPathResolverTest {
    @Test
    fun `absolute filesystem path resolves and reads`(
        @TempDir tempDir: Path,
    ) {
        val input = tempDir.resolve("process.txt").apply { writeText("Make toast") }

        val resolver = InputPathResolver(cwd = tempDir)

        assertEquals(input, resolver.resolve(input.pathString))
        assertEquals("Make toast", resolver.readUtf8(input.pathString))
    }

    @Test
    fun `relative filesystem path resolves from cwd`(
        @TempDir tempDir: Path,
    ) {
        val input =
            tempDir.resolve("nested/process.txt").also {
                it.parent.createDirectories()
                it.writeText("Approve invoice")
            }

        val resolver = InputPathResolver(cwd = tempDir)

        assertEquals(input, resolver.resolve("nested/process.txt"))
        assertEquals("Approve invoice", resolver.readUtf8("nested/process.txt"))
    }

    @Test
    fun `relative output path resolves from cwd`(
        @TempDir tempDir: Path,
    ) {
        val resolver = InputPathResolver(cwd = tempDir)

        assertEquals(tempDir.resolve("output.bpmn"), resolver.resolveOutputPath("output.bpmn"))
    }

    @Test
    fun `build working directory takes precedence over user dir`(
        @TempDir tempDir: Path,
    ) {
        val bazelWorkingDirectory = tempDir.resolve("workspace").also { it.createDirectories() }
        val userDir = tempDir.resolve("runfiles").also { it.createDirectories() }
        val input = bazelWorkingDirectory.resolve("process.txt").apply { writeText("Workspace input") }
        userDir.resolve("process.txt").apply { writeText("Runfiles input") }
        val environment = mutableMapOf<String, String>()

        val resolver =
            InputPathResolver(
                environment = environment,
                userDir = userDir.pathString,
            )
        environment["BUILD_WORKING_DIRECTORY"] = bazelWorkingDirectory.pathString

        assertEquals(input, resolver.resolve("process.txt"))
        assertEquals(bazelWorkingDirectory.resolve("output.bpmn"), resolver.resolveOutputPath("output.bpmn"))
    }

    @Test
    fun `user dir is fallback when build working directory is absent`(
        @TempDir tempDir: Path,
    ) {
        val input = tempDir.resolve("process.txt").apply { writeText("User dir input") }

        val resolver =
            InputPathResolver(
                environment = emptyMap(),
                userDir = tempDir.pathString,
            )

        assertEquals(input, resolver.resolve("process.txt"))
        assertEquals(tempDir.resolve("output.bpmn"), resolver.resolveOutputPath("output.bpmn"))
    }

    @Test
    fun `missing filesystem path falls back to raw runfiles key`(
        @TempDir tempDir: Path,
    ) {
        val runfiles =
            createRunfiles(tempDir) {
                resolve("sample/process.txt").apply {
                    parent.createDirectories()
                    writeText("Runfiles raw")
                }
            }

        val resolver = InputPathResolver(cwd = tempDir, runfilesLoader = { runfiles })

        assertEquals("Runfiles raw", resolver.readUtf8("sample/process.txt"))
    }

    @Test
    fun `main-prefixed runfiles variant resolves correctly`(
        @TempDir tempDir: Path,
    ) {
        val runfiles =
            createRunfiles(tempDir) {
                resolve("_main/style-guide.md").apply {
                    parent.createDirectories()
                    writeText("Use sentence case")
                }
            }

        val resolver = InputPathResolver(cwd = tempDir, runfilesLoader = { runfiles })

        assertEquals("Use sentence case", resolver.readUtf8("style-guide.md"))
    }

    @Test
    fun `workspace-prefixed runfiles variant resolves correctly`(
        @TempDir tempDir: Path,
    ) {
        val runfiles =
            createRunfiles(tempDir) {
                resolve("_main/toast-process.txt").apply {
                    parent.createDirectories()
                    writeText("Workspace mapped")
                }
                resolve("_repo_mapping").writeText(",bpmner,_main\n")
            }

        val resolver = InputPathResolver(cwd = tempDir, runfilesLoader = { runfiles })

        assertEquals("Workspace mapped", resolver.readUtf8("toast-process.txt"))
    }

    @Test
    fun `missing path reports filesystem and runfiles attempts`(
        @TempDir tempDir: Path,
    ) {
        val runfiles = createRunfiles(tempDir) {}
        val resolver = InputPathResolver(cwd = tempDir, runfilesLoader = { runfiles })

        val error =
            assertThrows<IllegalArgumentException> {
                resolver.resolve("missing.txt")
            }

        assertTrue(error.message!!.contains("filesystem path ${tempDir.resolve("missing.txt")}"))
        assertTrue(error.message!!.contains("Bazel runfile missing.txt"))
        assertTrue(error.message!!.contains("Bazel runfile _main/missing.txt"))
        assertTrue(error.message!!.contains("Bazel runfile bpmner/missing.txt"))
    }

    private fun createRunfiles(
        tempDir: Path,
        configure: Path.() -> Unit,
    ): Runfiles {
        val runfilesDir = tempDir.resolve("app.runfiles").apply { createDirectories() }
        runfilesDir.resolve("_repo_mapping").createFile()
        runfilesDir.configure()
        return Runfiles.preload(mapOf("RUNFILES_DIR" to runfilesDir.pathString)).withSourceRepository("")
    }
}
