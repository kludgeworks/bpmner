/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

@PackageInfo
@ApplicationModule(
    displayName = "BPMN Layout",
    allowedDependencies = ["core", "validation", "repair", "observability"],
    type = ApplicationModule.Type.OPEN,
)
internal class ModuleMetadata
