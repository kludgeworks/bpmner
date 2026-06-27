/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.pipeline.internal.adapter.inbound

import org.jline.reader.LineReader
import org.slf4j.LoggerFactory
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
 * Returning **false** when no [LineReader] is present is what makes non-interactive/CI/piped
 * runs skip the preview without blocking on stdin — this prompt is the sole interactivity gate.
 */
@Service
internal open class ShellPreviewPrompt(
    private val lineReaderProvider: ObjectProvider<LineReader>,
    private val lineRead: (LineReader) -> String? = { lr ->
        runCatching { lr.readLine("Open preview in browser? [Y/n]: ") }.getOrElse { "" }
    },
) : PreviewPrompt {
    private val logger = LoggerFactory.getLogger(ShellPreviewPrompt::class.java)

    override fun confirmOpenPreview(): Boolean {
        val lr = lineReaderProvider.ifAvailable
        if (lr == null) {
            logger.debug("[preview] confirmOpenPreview = false — no LineReader available (non-interactive context)")
            return false
        }
        val answer = lineRead(lr) ?: ""
        val confirmed = answer.isBlank() || answer.trim().lowercase().let { it == "y" || it == "yes" }
        logger.debug("[preview] confirmOpenPreview — answer='{}' -> {}", answer, confirmed)
        return confirmed
    }
}
