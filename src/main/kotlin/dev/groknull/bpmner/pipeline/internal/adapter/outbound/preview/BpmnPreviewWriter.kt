/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.pipeline.internal.adapter.outbound.preview

import org.jmolecules.architecture.onion.simplified.ApplicationRing
import java.nio.file.Path

/** Writes a transient HTML preview for a generated BPMN file into a temp dir and returns its path. */
@ApplicationRing
fun interface BpmnPreviewWriter {
    fun writePreview(bpmnPath: Path): Path
}
