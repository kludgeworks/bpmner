/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.orchestration

import org.springframework.modulith.ApplicationModule

@ApplicationModule(
    allowedDependencies = ["alignment", "bpmn", "contract", "generation", "layout", "readiness", "repair", "validation"],
)
object OrchestrationModule
