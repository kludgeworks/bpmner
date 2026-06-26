/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.pipeline.internal.domain

import dev.groknull.bpmner.browser.BrowserOpenOutcome
import dev.groknull.bpmner.browser.BrowserOpenPort
import dev.groknull.bpmner.browser.InteractiveEnvironment
import dev.groknull.bpmner.pipeline.internal.adapter.inbound.PreviewPrompt
import dev.groknull.bpmner.preview.BpmnPreviewWriter
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Application-layer service that orchestrates the post-generation BPMN preview flow.
 *
 * Sits between the [dev.groknull.bpmner.pipeline.internal.adapter.inbound.BpmnShellCommands]
 * primary adapter and the secondary ports ([BpmnPreviewWriter], [BrowserOpenPort],
 * [InteractiveEnvironment]) so the adapter is not required to reference secondary-port types
 * directly (which would violate the jMolecules hexagonal architecture rule).
 *
 * Gate order (never hangs automation):
 * 1. Interactive gate — [InteractiveEnvironment.canOpenBrowser] must be true.
 * 2. User opt-in — [PreviewPrompt.confirmOpenPreview] must return true.
 * 3. Path existence — the resolved BPMN file must exist on disk.
 *
 * Returns a [PreviewResult] describing what happened so the shell adapter can append
 * appropriate text without knowing the secondary-port outcome types.
 */
@Component
internal class BpmnPreviewOrchestrator(
    private val previewWriter: BpmnPreviewWriter,
    private val browserOpenPort: BrowserOpenPort,
    private val interactiveEnvironment: InteractiveEnvironment,
    private val previewPrompt: PreviewPrompt,
) {
    /** Run the full preview flow for the given BPMN file name (as recovered from the marker). */
    fun runPreviewFlow(bpmnFileName: String): PreviewResult {
        if (!interactiveEnvironment.canOpenBrowser() || !previewPrompt.confirmOpenPreview()) {
            return PreviewResult.Skipped
        }
        val bpmnPath = resolveBpmnPath(bpmnFileName)
            ?.takeIf { Files.exists(it) }
            ?: return PreviewResult.Skipped
        val writeResult = runCatching { previewWriter.writePreview(bpmnPath) }
        val previewPath = writeResult.getOrNull()
            ?: return PreviewResult.WriteFailed(
                bpmnPath,
                "Preview write failed: ${writeResult.exceptionOrNull()?.message ?: "unknown error"}",
            )
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
        is BrowserOpenOutcome.Unsupported ->
            PreviewResult.Fallback(previewPath, "browser not supported: ${outcome.reason}")
        is BrowserOpenOutcome.Failed ->
            PreviewResult.Fallback(previewPath, "Browser launch failed: ${outcome.reason}")
    }

    /** Result of the preview flow, expressed without referencing secondary-port outcome types. */
    sealed interface PreviewResult {
        /** No preview was attempted (non-interactive, user declined, or path not found). */
        data object Skipped : PreviewResult

        /** Preview written and browser launched successfully. */
        data class Opened(val previewPath: Path) : PreviewResult

        /** Preview written but browser launch was unsupported or failed; show the path manually. */
        data class Fallback(val previewPath: Path, val reason: String) : PreviewResult

        /**
         * Preview write failed after the user opted in; [bpmnPath] is the source BPMN file,
         * [reason] describes the failure so the user can act on it.
         */
        data class WriteFailed(val bpmnPath: Path, val reason: String) : PreviewResult
    }
}
