/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.readiness

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

@PackageInfo
@ApplicationModule(
    displayName = "BPMN Readiness",
    allowedDependencies = ["core"],
)
internal class ModuleMetadata
