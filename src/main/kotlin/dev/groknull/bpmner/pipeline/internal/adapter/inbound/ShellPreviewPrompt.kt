/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.pipeline.internal.adapter.inbound

import org.jline.reader.LineReader
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service

/**
 * Production [PreviewPrompt] adapter that reads a yes/no answer from the interactive shell
 * terminal via an injected [lineRead] function.
 *
 * Injects [LineReader] via [ObjectProvider] so this bean instantiates even when jline is not
 * configured (e.g. non-shell `@SpringBootTest` / `@ApplicationModuleTest` contexts). When no
 * [LineReader] is available, the prompt defaults to **false** (skip preview) rather than
 * blocking on stdin — consistent with the non-interactive gate in
 * [dev.groknull.bpmner.pipeline.internal.domain.BpmnPreviewOrchestrator].
 *
 * Tests inject a deterministic [lineRead] lambda to exercise both opt-in and opt-out paths
 * without touching stdin.
 *
 * The caller ([dev.groknull.bpmner.pipeline.internal.domain.BpmnPreviewOrchestrator]) always
 * gates on [dev.groknull.bpmner.browser.InteractiveEnvironment.canOpenBrowser] before invoking
 * this prompt, so the [lineRead] default is only reachable in confirmed-interactive runs.
 */
@Service
internal open class ShellPreviewPrompt(
    private val lineReaderProvider: ObjectProvider<LineReader>,
    private val lineRead: (LineReader) -> String? = { lr ->
        runCatching { lr.readLine("Open preview in browser? [Y/n]: ") }.getOrElse { "" }
    },
) : PreviewPrompt {
    override fun confirmOpenPreview(): Boolean {
        val lr = lineReaderProvider.ifAvailable ?: return false
        val answer = lineRead(lr) ?: ""
        return answer.isBlank() || answer.trim().lowercase().let { it == "y" || it == "yes" }
    }
}
