/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.orchestration

import org.springframework.modulith.ApplicationModule

@ApplicationModule(
    allowedDependencies = ["alignment", "authoring", "bpmn", "conformance", "contract", "layout", "readiness", "repair"],
)
object OrchestrationModule
