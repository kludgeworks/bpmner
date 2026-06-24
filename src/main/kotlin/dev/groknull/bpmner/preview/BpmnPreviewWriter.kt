/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.preview

import java.nio.file.Path

/** Writes a durable HTML preview artifact beside a generated BPMN file. */
fun interface BpmnPreviewWriter {
    fun writePreview(bpmnPath: Path): Path
}
