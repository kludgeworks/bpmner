/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.preview

import org.jmolecules.architecture.hexagonal.SecondaryPort
import java.nio.file.Path

/** Writes a transient HTML preview for a generated BPMN file into a temp dir and returns its path. */
@SecondaryPort
fun interface BpmnPreviewWriter {
    fun writePreview(bpmnPath: Path): Path
}
