/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.observability

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

@PackageInfo
@ApplicationModule(
    displayName = "BPMN Observability",
    allowedDependencies = ["alignment", "core", "generation", "readiness", "repair", "validation"],
)
internal class ModuleMetadata
