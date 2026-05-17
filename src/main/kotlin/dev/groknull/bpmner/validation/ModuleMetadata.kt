/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.validation

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

@PackageInfo
@ApplicationModule(
    displayName = "BPMN Validation",
    allowedDependencies = ["core", "contract"],
    type = ApplicationModule.Type.OPEN,
)
internal class ModuleMetadata
