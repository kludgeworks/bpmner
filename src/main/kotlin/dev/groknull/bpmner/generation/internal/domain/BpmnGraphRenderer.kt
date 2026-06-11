/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.domain

import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.RenderedBpmn
import dev.groknull.bpmner.generation.BpmnRenderer
import org.springframework.stereotype.Component

/**
 * Thin domain service that owns the [BpmnRenderer] secondary port.
 *
 * The deterministic graph→XML render previously sat inline in
 * [dev.groknull.bpmner.generation.internal.adapter.inbound.LlmBpmnProcessGenerator],
 * but a `@PrimaryAdapter` may not depend on a `@SecondaryPort` under the hexagonal
 * architecture rule (BpmnerModulithTest). Holding the port in a plain domain `@Component`
 * here — mirroring `repair/internal/domain/BpmnRepairAdvancer` — keeps the inbound adapter
 * clean while the generator delegates the actual render.
 */
@Component
internal class BpmnGraphRenderer(
    private val renderer: BpmnRenderer,
) {
    fun render(graph: LaidOutProcessGraph): RenderedBpmn = renderer.render(graph)
}
