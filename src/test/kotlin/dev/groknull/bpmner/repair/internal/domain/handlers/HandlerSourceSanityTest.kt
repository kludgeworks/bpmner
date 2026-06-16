/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("MaxLineLength")

package dev.groknull.bpmner.repair.internal.domain.handlers

import com.google.devtools.build.runfiles.Runfiles
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class HandlerSourceSanityTest {

    @Test
    fun `no BpmnLocalModelFixHandler reads staticConfig`() {
        val runfiles = Runfiles.preload().withSourceRepository("")
        val resolvedPath = runfiles.rlocation("bpmner/src/main/kotlin/dev/groknull/bpmner/repair/internal/domain/handlers/ClearNameHandler.kt")
            ?: runfiles.rlocation("_main/src/main/kotlin/dev/groknull/bpmner/repair/internal/domain/handlers/ClearNameHandler.kt")
            ?: runfiles.rlocation("src/main/kotlin/dev/groknull/bpmner/repair/internal/domain/handlers/ClearNameHandler.kt")

        assertTrue(resolvedPath != null, "Could not resolve ClearNameHandler.kt via runfiles")

        val sampleFile = File(resolvedPath)
        assertTrue(sampleFile.exists(), "Resolved ClearNameHandler.kt must exist at: $resolvedPath")

        val handlersDir = sampleFile.parentFile
        assertTrue(handlersDir.exists(), "Handlers directory must exist at: ${handlersDir.absolutePath}")

        val files = handlersDir.listFiles { _, name -> name.endsWith(".kt") }
            ?: emptyArray()
        assertTrue(files.isNotEmpty(), "Handlers directory should not be empty")

        val filesUsingStaticConfig = files.filter { file ->
            val content = file.readText()
            content.contains("staticConfig")
        }

        assertTrue(
            filesUsingStaticConfig.isEmpty(),
            "Found BpmnLocalModelFixHandler implementations reading staticConfig: ${filesUsingStaticConfig.map { it.name }}",
        )
    }
}
