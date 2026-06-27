/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.pipeline.internal.domain

import dev.groknull.bpmner.browser.BrowserOpenOutcome
import dev.groknull.bpmner.browser.BrowserOpenPort
import dev.groknull.bpmner.pipeline.internal.adapter.inbound.PreviewPrompt
import dev.groknull.bpmner.preview.BpmnPreviewWriter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Application-layer service that orchestrates the post-generation BPMN preview flow.
 *
 * Sits between the [dev.groknull.bpmner.pipeline.internal.adapter.inbound.BpmnShellCommands]
 * primary adapter and the secondary ports ([BpmnPreviewWriter], [BrowserOpenPort]) so the adapter
 * is not required to reference secondary-port types directly (which would violate the jMolecules
 * hexagonal architecture rule).
 *
 * The preview is a best-effort, nice-to-have: any miss returns a [PreviewResult] the shell can
 * report, never an exception.
 *
 * Flow (never hangs automation):
 * 1. User opt-in — [PreviewPrompt.confirmOpenPreview] must return true. In non-interactive runs
 *    (no terminal `LineReader`) this returns false, so CI/test/piped runs skip without blocking.
 * 2. Path existence — the resolved BPMN file must exist on disk.
 * 3. Write the HTML preview, then ask the OS to open it.
 */
@Component
internal class BpmnPreviewOrchestrator(
    private val previewWriter: BpmnPreviewWriter,
    private val browserOpenPort: BrowserOpenPort,
    private val previewPrompt: PreviewPrompt,
) {
    private val logger = LoggerFactory.getLogger(BpmnPreviewOrchestrator::class.java)

    /** Run the full preview flow for the given BPMN file name (as recovered from the marker). */
    fun runPreviewFlow(bpmnFileName: String): PreviewResult {
        logger.debug("[preview] runPreviewFlow for '{}'", bpmnFileName)

        if (!previewPrompt.confirmOpenPreview()) {
            logger.debug("[preview] skipped — user declined or no interactive prompt available")
            return PreviewResult.Skipped
        }

        val bpmnPath = resolveBpmnPath(bpmnFileName)?.takeIf { Files.exists(it) }
            ?: run {
                logger.debug("[preview] skipped — BPMN path missing or unresolvable for '{}'", bpmnFileName)
                return PreviewResult.Skipped
            }

        val writeResult = runCatching { previewWriter.writePreview(bpmnPath) }
        val previewPath = writeResult.getOrNull()
            ?: run {
                logger.warn("[preview] preview write failed for {}", bpmnPath, writeResult.exceptionOrNull())
                return PreviewResult.WriteFailed(
                    bpmnPath,
                    "Preview write failed: ${writeResult.exceptionOrNull()?.message ?: "unknown error"}",
                )
            }

        logger.debug("[preview] preview written to {} — opening browser", previewPath)
        return outcomeToResult(previewPath, browserOpenPort.open(previewPath))
    }

    private fun resolveBpmnPath(name: String): Path? {
        if (name.isBlank()) return null
        return runCatching {
            Paths.get(name).let { p -> if (p.isAbsolute) p else Paths.get("").toAbsolutePath().resolve(p) }
        }.getOrNull()
    }

    private fun outcomeToResult(previewPath: Path, outcome: BrowserOpenOutcome): PreviewResult = when (outcome) {
        is BrowserOpenOutcome.Opened ->
            PreviewResult.Opened(previewPath)
        is BrowserOpenOutcome.Failed ->
            PreviewResult.Fallback(previewPath, "Browser launch failed: ${outcome.reason}")
    }

    /** Result of the preview flow, expressed without referencing secondary-port outcome types. */
    sealed interface PreviewResult {
        /** No preview was attempted (user declined, non-interactive, or path not found). */
        data object Skipped : PreviewResult

        /** Preview written and browser launched successfully. */
        data class Opened(val previewPath: Path) : PreviewResult

        /** Preview written but the browser launch failed; show the path so the user can open it. */
        data class Fallback(val previewPath: Path, val reason: String) : PreviewResult

        /**
         * Preview write failed after the user opted in; [bpmnPath] is the source BPMN file,
         * [reason] describes the failure so the user can act on it.
         */
        data class WriteFailed(val bpmnPath: Path, val reason: String) : PreviewResult
    }
}
