/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout

import org.springframework.modulith.ApplicationModule

/**
 * Layout module — graph layout pass that decorates the BPMN definition with
 * visual coordinates. Surfaces additional structural issues for downstream
 * handling; the orchestrator invokes repair separately if needed.
 */
@ApplicationModule(allowedDependencies = ["bpmn", "validation"])
internal object LayoutModule
