/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.domain

import dev.groknull.bpmner.domain.LaidOutProcessGraph
import dev.groknull.bpmner.domain.RenderedBpmn
import dev.groknull.bpmner.generation.BpmnRenderer
import org.springframework.stereotype.Component

/**
 * Thin domain service that owns the [BpmnRenderer] secondary port.
 *
 * A `@PrimaryAdapter` may not depend on a `@SecondaryPort` under the hexagonal architecture rule
 * (BpmnerModulithTest), so the port lives in a plain domain `@Component` here — mirroring
 * `repair/internal/domain/BpmnRepairAdvancer` — and `LlmBpmnProcessGenerator` delegates rendering to it.
 */
@Component
internal class BpmnGraphRenderer(
    private val renderer: BpmnRenderer,
) {
    fun render(graph: LaidOutProcessGraph): RenderedBpmn = renderer.render(graph)
}
