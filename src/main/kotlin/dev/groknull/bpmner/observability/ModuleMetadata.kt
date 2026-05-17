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
    allowedDependencies = ["core", "validation", "generation", "repair", "alignment", "readiness"],
    type = ApplicationModule.Type.OPEN,
)
internal class ModuleMetadata
