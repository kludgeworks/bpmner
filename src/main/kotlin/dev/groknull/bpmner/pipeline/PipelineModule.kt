/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.pipeline

import org.springframework.modulith.ApplicationModule

@ApplicationModule(
    allowedDependencies = [
        "alignment", "authoring", "bpmn", "browser",
        "conformance", "contract", "layout", "preview",
        "readiness", "repair",
    ],
)
object PipelineModule
