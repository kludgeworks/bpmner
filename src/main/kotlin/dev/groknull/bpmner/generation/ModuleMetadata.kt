/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

@PackageInfo
@ApplicationModule(
    displayName = "BPMN Generation",
    allowedDependencies = ["core", "readiness", "alignment", "validation", "contract"],
    type = ApplicationModule.Type.OPEN,
)
internal class ModuleMetadata
